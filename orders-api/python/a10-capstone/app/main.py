"""A10 capstone: everything from the API track in one service.

    Orders with ETags and conditional writes (A4), cursor pagination (A5), problem details (A2),
    idempotent payments (A3), request ids and per-client rate limits (A9).

Run:  docker compose up -d && uv run uvicorn app.main:app --port 8000
Test: BASE_URL=http://localhost:8000 uv run pytest   (in ../../conformance)
"""

import base64
import binascii
import hashlib
import json
import math
import os
import re
import threading
import time
import uuid
from contextlib import asynccontextmanager
from urllib.parse import urlencode

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException
from starlette.routing import Match

from . import db, idempotency, repository
from .gateway import gateway
from .http import (JSON, MERGE_PATCH, ApiError, api_error_handler, problem, require_accept_json,
                   require_content_type, validation_handler)
from .models import Order, OrderInput

# Clients may keep a copy but must check it's still current before using it.
REVALIDATE = "private, no-cache"


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
    yield
    db.pool.close()


app = FastAPI(title="Orders API (A10 capstone)", lifespan=lifespan, dependencies=[Depends(require_accept_json)])
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
    if current.status in ("paid", "paying"):
        raise ApiError(409, "order-locked", f"order {public_id} is {current.status} and can't be changed")
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
    if status is not None and status not in ("pending", "paying", "paid", "cancelled"):
        raise bad("status must be one of pending, paying, paid, cancelled")
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


@app.get("/orders/{public_id}/payments")
def list_payments(public_id: str):
    order = existing(public_id)
    return {"data": [p.model_dump(by_alias=True, mode="json") for p in repository.payments(order_id(order.id))]}


# POST isn't idempotent by itself; the Idempotency-Key makes this one safe to retry.
@app.post("/orders/{public_id}/payments", status_code=201, dependencies=[Depends(json_body)])
async def create_payment(public_id: str, request: Request):
    key = request.headers.get("idempotency-key", "").strip()
    if not key:
        raise ApiError(400, "idempotency-key-missing",
                       "send an Idempotency-Key header (a fresh UUID per payment) so retries can't charge twice")
    raw = await request.body()
    # This handler is async (to read the raw body), so every blocking call (Postgres, the slow
    # gateway) runs in the thread pool. Blocking the event loop here would stall every request.
    # Same key must mean same request: fingerprint the method, path and body.
    fingerprint = hashlib.sha256(f"{request.method} {request.url.path}\n".encode() + raw).hexdigest()

    stored = await run_in_threadpool(idempotency.begin, key, fingerprint)  # 422 / 409 raised from here
    if stored is not None:
        return Response(stored.body, status_code=stored.status, media_type=JSON,
                        headers={"Location": stored.location or "", "Idempotent-Replayed": "true"})
    try:
        payment = await run_in_threadpool(charge, public_id, raw, key)
        body = payment.model_dump_json(by_alias=True)
        location = f"/orders/{public_id}/payments/{payment.id}"
        await run_in_threadpool(idempotency.complete, key, 201, body, location)
        return Response(body, status_code=201, media_type=JSON, headers={"Location": location})
    except Exception:
        await run_in_threadpool(idempotency.abandon, key)  # nothing was stored: let the client retry with the same key
        raise


def charge(public_id: str, raw: bytes, key: str):
    order = existing(public_id)
    try:
        amount = json.loads(raw).get("amount")
    except (ValueError, AttributeError):
        raise ApiError(400, "malformed-body", "request body is not valid JSON")
    if not isinstance(amount, int) or amount != order.total:
        raise ApiError(422, "validation-error", "validation failed",
                       {"#/amount": f"must equal the order total, {order.total}"})
    oid = order_id(public_id)
    if not repository.claim_for_payment(oid):
        raise ApiError(409, "order-not-payable", f"order {public_id} is already paid, being paid, or cancelled")
    try:
        charge_id = gateway.charge(key, public_id, order.total)
        return repository.record_payment(oid, order.total, order.currency, charge_id)
    except Exception as e:
        repository.release_claim(oid)
        raise ApiError(502, "payment-failed", f"the payment gateway failed: {e}")


CAPACITY = int(os.environ.get("RATELIMIT_CAPACITY", "100"))
RATE = float(os.environ.get("RATELIMIT_REFILL_PER_SECOND", "50"))
SAFE_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")


# ---------------------------------------------------------------- rate limiting
class TokenBucket:
    """Holds up to `capacity` tokens, refilled at `rate` per second. Each request takes one."""

    def __init__(self, capacity: int, rate: float):
        self.capacity, self.rate, self.tokens, self.last = capacity, rate, float(capacity), time.monotonic()
        self.lock = threading.Lock()

    def take(self) -> tuple[bool, int, int]:
        with self.lock:
            now = time.monotonic()
            self.tokens = min(self.capacity, self.tokens + (now - self.last) * self.rate)  # refill
            self.last = now
            if self.tokens >= 1:
                self.tokens -= 1
                return True, math.floor(self.tokens), 0
            return False, 0, max(1, math.ceil((1 - self.tokens) / self.rate))


BUCKETS: dict[str, TokenBucket] = {}


@app.middleware("http")
async def rate_limit(request: Request, call_next):
    """One bucket per client: the API key, or the IP if there's no key. In memory, so per instance:
    N instances behind a load balancer give a client N times the limit. Shared limits need a shared store."""
    if request.method == "OPTIONS":
        return await call_next(request)  # CORS preflights are free
    key = request.headers.get("x-api-key")
    client = f"key:{key}" if key else f"ip:{request.client.host}"  # behind a proxy: trusted X-Forwarded-For only
    allowed, remaining, retry_after = BUCKETS.setdefault(client, TokenBucket(CAPACITY, RATE)).take()
    limits = {"X-RateLimit-Limit": str(CAPACITY), "X-RateLimit-Remaining": str(remaining)}
    if not allowed:
        return problem(request, 429, "rate-limited",
                       f"rate limit of {CAPACITY} requests, refilled at {RATE} per second; retry after {retry_after} s",
                       headers=limits | {"Retry-After": str(retry_after)})
    response = await call_next(request)
    response.headers.update(limits)
    return response


# ---------------------------------------------------------------- request ids (outermost)
@app.middleware("http")
async def request_id(request: Request, call_next):
    """Use the caller's X-Request-Id if it looks sane (it ends up in logs), otherwise make one.
    It goes into the response header and every problem body, tying a user's report to the logs."""
    incoming = request.headers.get("x-request-id", "")
    request.state.request_id = incoming if SAFE_ID.fullmatch(incoming) else str(uuid.uuid4())
    response = await call_next(request)
    response.headers["X-Request-Id"] = request.state.request_id
    return response
