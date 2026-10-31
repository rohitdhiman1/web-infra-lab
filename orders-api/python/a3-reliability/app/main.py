"""A3: orders in Postgres, and payments made safe to retry with Idempotency-Key.

Run:  docker compose up -d && uv run uvicorn app.main:app --port 8000
"""

import hashlib
import json
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException

from . import db, idempotency, repository
from .gateway import gateway
from .http import JSON, ApiError, api_error_handler, require_accept_json, require_content_type, validation_handler
from .models import Order, OrderInput


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
    yield
    db.pool.close()


app = FastAPI(title="Orders API (A3)", lifespan=lifespan, dependencies=[Depends(require_accept_json)])
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    return await api_error_handler(request, ApiError(e.status_code, None, str(e.detail), headers=dict(e.headers or {})))


def json_body(request: Request) -> None:
    require_content_type(request, JSON)


def order_id(public_id: str) -> int:
    """Public ids are opaque strings ("ord_12"); the database uses numbers."""
    if public_id.startswith("ord_") and public_id[4:].isdigit():
        return int(public_id[4:])
    raise ApiError(404, "order-not-found", f"order {public_id} not found")


def existing(public_id: str) -> Order:
    order = repository.find_order(order_id(public_id))
    if order is None:
        raise ApiError(404, "order-not-found", f"order {public_id} not found")
    return order


@app.post("/orders", status_code=201, response_model=Order, dependencies=[Depends(json_body)])
def create_order(body: OrderInput, response: Response):
    if problems := body.problems():
        raise ApiError(422, "validation-error", "validation failed", problems)
    order = repository.create_order(body)
    response.headers["Location"] = f"/orders/{order.id}"
    return order


@app.get("/orders/{public_id}", response_model=Order)
def get_order(public_id: str):
    return existing(public_id)


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
        raise ApiError(409, "order-already-paid", f"order {public_id} is already paid or being paid")
    try:
        charge_id = gateway.charge(key, public_id, order.total)
        return repository.record_payment(oid, order.total, order.currency, charge_id)
    except Exception as e:
        repository.release_claim(oid)
        raise ApiError(502, "payment-failed", f"the payment gateway failed: {e}")
