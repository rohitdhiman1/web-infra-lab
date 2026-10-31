"""A4: every order carries an ETag.

    GET   + If-None-Match  -> 304 Not Modified if unchanged (saves the body)
    PATCH + If-Match       -> 412 if someone changed it since you read it (no lost updates)
    PATCH without If-Match -> 428: this API insists on conditional writes

Run:  docker compose up -d && uv run uvicorn app.main:app --port 8000
"""

import json
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException

from . import db, repository
from .http import (JSON, MERGE_PATCH, ApiError, api_error_handler, require_accept_json, require_content_type,
                   validation_handler)
from .models import Order, OrderInput

# Clients may keep a copy but must check it's still current before using it.
REVALIDATE = "private, no-cache"


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
    yield
    db.pool.close()


app = FastAPI(title="Orders API (A4)", lifespan=lifespan, dependencies=[Depends(require_accept_json)])
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    return await api_error_handler(request, ApiError(e.status_code, None, str(e.detail), headers=dict(e.headers or {})))


def json_body(request: Request) -> None:
    require_content_type(request, JSON)


def order_id(public_id: str) -> int:
    if public_id.startswith("ord_") and public_id[4:].isdigit():
        return int(public_id[4:])
    raise ApiError(404, "order-not-found", f"order {public_id} not found")


def existing(public_id: str) -> Order:
    order = repository.find_order(order_id(public_id))
    if order is None:
        raise ApiError(404, "order-not-found", f"order {public_id} not found")
    return order


def matches(header: str | None, etag: str) -> bool:
    """If-Match / If-None-Match: "*" or a comma-separated list of ETags."""
    if header is None:
        return False
    return any(t.strip() in ("*", etag) for t in header.split(","))


def order_response(order: Order, status: int = 200, headers: dict | None = None) -> JSONResponse:
    return JSONResponse(order.model_dump(by_alias=True, mode="json"), status_code=status,
                        headers={"ETag": order.etag, "Cache-Control": REVALIDATE, **(headers or {})})


@app.post("/orders", status_code=201, response_model=Order, dependencies=[Depends(json_body)])
def create_order(body: OrderInput):
    problems = body.problems(status_required=False)
    problems.pop("#/status", None)
    if problems:
        raise ApiError(422, "validation-error", "validation failed", problems)
    order = repository.create_order(body)
    return order_response(order, 201, {"Location": f"/orders/{order.id}"})


@app.get("/orders/{public_id}", response_model=Order)
def get_order(public_id: str, request: Request):
    order = existing(public_id)
    if matches(request.headers.get("if-none-match"), order.etag):
        # Unchanged: no body, just the validators. The client reuses its copy.
        return Response(status_code=304, headers={"ETag": order.etag, "Cache-Control": REVALIDATE})
    return order_response(order)


@app.patch("/orders/{public_id}", response_model=Order)
async def patch_order(public_id: str, request: Request):
    require_content_type(request, MERGE_PATCH, header="Accept-Patch")
    raw = await request.body()
    return await run_in_threadpool(apply_patch, public_id, request.headers.get("if-match"), raw)


def apply_patch(public_id: str, if_match: str | None, raw: bytes):
    current = existing(public_id)
    if if_match is None:
        raise ApiError(428, "precondition-required",
                       "send If-Match with the ETag you last read, so concurrent edits can't overwrite each other")
    if not matches(if_match, current.etag):
        raise ApiError(412, "precondition-failed",
                       f"order {public_id} is now at version {current.etag}; GET it again and reapply your change")
    try:
        patch = json.loads(raw)
        assert isinstance(patch, dict)
    except (ValueError, AssertionError):
        raise ApiError(400, "malformed-body", "request body is not a JSON object")
    merged_raw = current.input().model_dump(by_alias=True) | {
        k: v for k, v in patch.items() if k in ("customer", "currency", "items", "status")}
    try:
        merged = OrderInput.model_validate(merged_raw)
    except ValidationError:
        raise ApiError(422, "validation-error", "request body has the wrong shape")
    if problems := merged.problems(status_required=True):
        raise ApiError(422, "validation-error", "validation failed", problems)
    # Re-check in the UPDATE itself: another writer may have slipped in since we read `current`.
    if not repository.update_if_version(order_id(public_id), current.version, merged):
        raise ApiError(412, "precondition-failed", f"order {public_id} changed while your update was being applied")
    return order_response(existing(public_id))
