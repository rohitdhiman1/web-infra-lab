"""A5: A4 plus GET /orders, newest first, one page at a time.

    ?limit=1..100 (default 20)   ?status=pending|cancelled
    ?cursor=<opaque>  keyset pagination (use this)
    ?offset=<n>       offset pagination (kept to show why cursors win)

From A4, every order carries an ETag.

    GET   + If-None-Match  -> 304 Not Modified if unchanged (saves the body)
    PATCH + If-Match       -> 412 if someone changed it since you read it (no lost updates)
    PATCH without If-Match -> 428: this API insists on conditional writes

Run:  docker compose up -d && uv run uvicorn app.main:app --port 8000
"""

import base64
import binascii
import json
from urllib.parse import urlencode
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException
from starlette.routing import Match

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


app = FastAPI(title="Orders API (A5)", lifespan=lifespan, dependencies=[Depends(require_accept_json)])
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    """Starlette's 405 lists only the methods of the *first* route on that path (see A1).
    RFC 9110 says Allow must list every method the resource supports, so collect them all."""
    headers = dict(e.headers or {})
    if e.status_code == 405:
        methods = set()
        for route in app.router.routes:
            match, _ = route.matches({"type": "http", "path": request.url.path, "method": "\u0000"})
            if match != Match.NONE:
                methods |= getattr(route, "methods", set()) or set()
        headers["Allow"] = ", ".join(sorted(methods))
    return await api_error_handler(request, ApiError(e.status_code, None, str(e.detail), headers=headers))


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


def bad(message: str) -> ApiError:
    return ApiError(400, "invalid-parameter", message)


def int_param(name: str, raw: str | None, default: int, lo: int, hi: int) -> int:
    if raw is None:
        return default
    if raw.isdigit() and lo <= int(raw) <= hi:
        return int(raw)
    raise bad(f"{name} must be a whole number from {lo} to {hi}")


def encode_cursor(last: Order) -> str:
    """Cursors are opaque to clients: base64url of the last id seen. Clients must not build or parse them."""
    return base64.urlsafe_b64encode(f"id:{last.id.removeprefix('ord_')}".encode()).decode().rstrip("=")


def decode_cursor(cursor: str) -> int:
    try:
        raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode()
        if raw.startswith("id:") and raw[3:].isdigit():
            return int(raw[3:])
    except (binascii.Error, UnicodeDecodeError, ValueError):
        pass
    raise bad("cursor is not valid; use the next link from the previous page")


def link(limit: int, status: str | None, name: str, value: str) -> str:
    params = {"limit": limit} | ({"status": status} if status else {}) | {name: value}
    return "/orders?" + urlencode(params)


@app.get("/orders")
def list_orders(limit: str | None = None, cursor: str | None = None, offset: str | None = None,
                status: str | None = None):
    size = int_param("limit", limit, 20, 1, 100)
    if status is not None and status not in ("pending", "cancelled"):
        raise bad("status must be one of pending, cancelled")
    if cursor is not None and offset is not None:
        raise bad("send cursor or offset, not both")
    next_link = None
    # Ask for one extra row: if it comes back, there's a next page.
    if offset is not None:
        start = int_param("offset", offset, 0, 0, 2**31 - 1)
        rows = repository.page_at_offset(start, status, size + 1)
        if len(rows) > size:
            next_link = link(size, status, "offset", str(start + size))
    else:
        rows = repository.page_after(decode_cursor(cursor) if cursor else None, status, size + 1)
        if len(rows) > size:
            next_link = link(size, status, "cursor", encode_cursor(rows[size - 1]))
    body = {"data": [o.model_dump(by_alias=True, mode="json") for o in rows[:size]], "next": next_link}
    headers = {"Link": f'<{next_link}>; rel="next"'} if next_link else {}
    return JSONResponse(body, headers=headers)
