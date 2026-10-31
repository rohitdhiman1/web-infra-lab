"""A1: the full method set with deliberate status codes.

Run:  uv run uvicorn app.main:app --port 8000
"""

import json

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
from starlette.exceptions import HTTPException
from starlette.routing import Match

from .http import (JSON, MERGE_PATCH, ApiError, api_error_handler, require_accept_json,
                   require_content_type, validation_handler)
from .models import Order, OrderInput, OrderList
from .store import OrderStore, build

app = FastAPI(title="Orders API (A1)", dependencies=[Depends(require_accept_json)])
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)
store = OrderStore()


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    """Starlette's 405 lists only the methods of the *first* route on that path.
    RFC 9110 says Allow must list every method the resource supports, so collect them all."""
    headers = dict(e.headers or {})
    if e.status_code == 405:
        methods = set()
        for route in app.router.routes:
            match, _ = route.matches({"type": "http", "path": request.url.path, "method": "\u0000"})
            if match != Match.NONE:
                methods |= getattr(route, "methods", set()) or set()
        headers["Allow"] = ", ".join(sorted(methods))
    return await api_error_handler(request, ApiError(e.status_code, str(e.detail), headers=headers))


CLIENT_FIELDS = ("customer", "currency", "items", "status")


def json_body(request: Request) -> None:
    require_content_type(request, JSON)


def existing(order_id: str) -> Order:
    order = store.find(order_id)
    if order is None:
        raise ApiError(404, f"order {order_id} not found")
    return order


def apply(current: Order, nxt: OrderInput) -> Order:
    """A cancelled order is final. Repeating the same change is a no-op (200), so retries are safe."""
    if nxt == current.input():
        return current
    if current.status == "cancelled":
        raise ApiError(409, f"order {current.id} is cancelled and can't be changed")
    updated = build(current.id, nxt, nxt.status, current.created_at)
    store.save(updated)
    return updated


@app.get("/orders", response_model=OrderList)
def list_orders():
    return OrderList(data=store.all())


# POST: not idempotent. Each call creates a new order with a new id.
@app.post("/orders", status_code=201, response_model=Order, dependencies=[Depends(json_body)])
def create_order(body: OrderInput, response: Response):
    problems = body.problems(status_required=False)
    problems.pop("status", None)  # status can't be chosen on create
    if problems:
        raise ApiError(422, "validation failed", problems)
    order = store.create(body.model_copy(update={"status": None}))
    response.headers["Location"] = f"/orders/{order.id}"
    return order


@app.get("/orders/{order_id}", response_model=Order)
def get_order(order_id: str):
    return existing(order_id)


# FastAPI doesn't add HEAD for GET routes. Register it separately (Starlette drops the body),
# and keep it out of the OpenAPI document, where it would duplicate the GET operation.
app.head("/orders/{order_id}", include_in_schema=False)(get_order)


# PUT: replace every client-settable field. Idempotent: sending it twice leaves the same state.
@app.put("/orders/{order_id}", response_model=Order, dependencies=[Depends(json_body)])
def replace_order(order_id: str, body: OrderInput):
    current = existing(order_id)
    problems = body.problems(status_required=True)
    if problems:
        raise ApiError(422, "validation failed", problems)
    return apply(current, body)


# PATCH with JSON Merge Patch (RFC 7396): only the fields you send change.
@app.patch("/orders/{order_id}", response_model=Order)
async def patch_order(order_id: str, request: Request):
    require_content_type(request, MERGE_PATCH, header="Accept-Patch")
    current = existing(order_id)
    try:
        patch = json.loads(await request.body())
    except ValueError:
        raise ApiError(400, "request body is not valid JSON")
    if not isinstance(patch, dict):
        raise ApiError(400, "request body is not a JSON object")
    for field in CLIENT_FIELDS:
        if field in patch and patch[field] is None:
            raise ApiError(422, "validation failed", {field: "is required and can't be removed"})
    merged = current.input().model_dump(by_alias=True) | {k: v for k, v in patch.items() if k in CLIENT_FIELDS}
    try:
        nxt = OrderInput.model_validate(merged)
    except ValidationError:
        raise ApiError(422, "request body has the wrong shape")
    problems = nxt.problems(status_required=True)
    if problems:
        raise ApiError(422, "validation failed", problems)
    return apply(current, nxt)


# DELETE: idempotent in effect. The first call removes it (204); later calls find nothing (404).
@app.delete("/orders/{order_id}", status_code=204)
def delete_order(order_id: str):
    if not store.delete(order_id):
        raise ApiError(404, f"order {order_id} not found")
    return Response(status_code=204)
