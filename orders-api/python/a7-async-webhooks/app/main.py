"""A7: work that takes too long for one request.

    POST /refunds       -> 202 Accepted + Location of a status resource + Retry-After
    GET  /refunds/{id}  -> the status ("pending" with Retry-After, then "succeeded")
    POST /webhooks      -> subscribe a URL; the response holds the signing secret (shown once)

When a refund completes, every subscriber gets a signed "refund.succeeded" event, retried with
exponential backoff until it answers 2xx: at-least-once delivery.

Run:  uv run uvicorn app.main:app --port 8000
"""

import itertools
import json
import os
import threading
import time
from datetime import UTC, datetime

import httpx2
from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel
from starlette.exceptions import HTTPException

from . import webhooks
from .http import JSON, ApiError, api_error_handler, require_accept_json, require_content_type, validation_handler

CONFIG = {
    "refund_delay_ms": int(os.environ.get("REFUND_DELAY_MS", "2000")),
    "retry_base_ms": int(os.environ.get("WEBHOOK_RETRY_BASE_MS", "1000")),  # doubles each attempt
    "max_attempts": int(os.environ.get("WEBHOOK_MAX_ATTEMPTS", "5")),
}
REFUNDS: dict[str, dict] = {}
SUBSCRIPTIONS: list[dict] = []
IDS = itertools.count(1)
LOCK = threading.Lock()

app = FastAPI(title="Orders API (A7)", dependencies=[Depends(require_accept_json)])
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    return await api_error_handler(request, ApiError(e.status_code, None, str(e.detail), headers=dict(e.headers or {})))


def json_body(request: Request) -> None:
    require_content_type(request, JSON)


def now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class NewRefund(BaseModel):
    orderId: str | None = None
    amount: int | None = None


class NewWebhook(BaseModel):
    url: str | None = None


@app.post("/refunds", status_code=202, dependencies=[Depends(json_body)])
def start_refund(body: NewRefund, response: Response):
    if not body.orderId or not body.amount or body.amount < 1:
        raise ApiError(422, "validation-error", "validation failed", {"#/amount": "orderId and a positive amount are required"})
    refund = {"id": f"ref_{next(IDS)}", "orderId": body.orderId, "amount": body.amount, "status": "pending",
              "createdAt": now(), "completedAt": None}
    REFUNDS[refund["id"]] = refund
    threading.Timer(CONFIG["refund_delay_ms"] / 1000, complete, args=[refund["id"]]).start()
    # 202: accepted, not done. The Location is where to check on it, not the finished result.
    response.headers["Location"] = f"/refunds/{refund['id']}"
    response.headers["Retry-After"] = "1"
    return refund


@app.get("/refunds/{refund_id}")
def refund_status(refund_id: str, response: Response):
    refund = REFUNDS.get(refund_id)
    if refund is None:
        raise ApiError(404, "refund-not-found", f"refund {refund_id} not found")
    if refund["status"] == "pending":
        response.headers["Retry-After"] = "1"  # tell pollers how often to ask
    return refund


@app.post("/webhooks", status_code=201, dependencies=[Depends(json_body)])
def subscribe(body: NewWebhook, response: Response):
    url = body.url or ""
    if not (url.startswith("https://") or url.startswith(("http://localhost", "http://127.0.0.1"))):
        raise ApiError(422, "validation-error", "validation failed", {"#/url": "must be an https URL (plain http only for localhost)"})
    sub = {"id": f"wh_{next(IDS)}", "url": url, "secret": webhooks.new_secret()}
    SUBSCRIPTIONS.append(sub)
    response.headers["Location"] = f"/webhooks/{sub['id']}"
    return sub


def complete(refund_id: str) -> None:
    with LOCK:
        refund = REFUNDS[refund_id] | {"status": "succeeded", "completedAt": now()}
        REFUNDS[refund_id] = refund
    event = json.dumps({"type": "refund.succeeded", "data": refund}, separators=(",", ":"))
    for sub in list(SUBSCRIPTIONS):
        msg_id = f"msg_{refund_id}_{sub['id']}"  # same id on every retry of this event
        threading.Thread(target=deliver, args=(sub, msg_id, event), daemon=True).start()


def deliver(sub: dict, msg_id: str, body: str) -> None:
    with httpx2.Client(timeout=5) as client:
        for attempt in range(1, CONFIG["max_attempts"] + 1):
            ts = int(time.time())
            headers = {"Content-Type": "application/json", "webhook-id": msg_id, "webhook-timestamp": str(ts),
                       "webhook-signature": webhooks.sign(sub["secret"], msg_id, ts, body)}
            try:
                if client.post(sub["url"], content=body, headers=headers).is_success:
                    return
            except httpx2.TransportError:
                pass
            if attempt < CONFIG["max_attempts"]:
                time.sleep(CONFIG["retry_base_ms"] * 2 ** (attempt - 1) / 1000)  # 1x, 2x, 4x, 8x the base
    # After max_attempts a real system parks the event in a dead-letter list and alerts someone.
