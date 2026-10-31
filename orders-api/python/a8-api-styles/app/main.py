"""A8: the same orders, two styles.

    POST /orders          request/response (as before)
    GET  /orders/events   a Server-Sent Events stream of "order.created" events.
                          Reconnect with Last-Event-ID to resume where you left off.

Run:  uv run uvicorn app.main:app --port 8000 --timeout-graceful-shutdown 5
(an open stream never finishes by itself, so tell uvicorn not to wait for it forever on shutdown)
"""

import asyncio
import itertools
import json
import os
from datetime import UTC, datetime

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from starlette.exceptions import HTTPException

from .http import JSON, ApiError, api_error_handler, require_content_type, validation_handler

HEARTBEAT_SECONDS = float(os.environ.get("EVENTS_HEARTBEAT_SECONDS", "15"))
LOG: list[dict] = []                 # append-only: every event, with an increasing id
STREAMS: set[asyncio.Queue] = set()  # one queue per open stream
IDS = itertools.count(1)

app = FastAPI(title="Orders API (A8)")
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    return await api_error_handler(request, ApiError(e.status_code, None, str(e.detail), headers=dict(e.headers or {})))


class Item(BaseModel):
    sku: str
    quantity: int
    unitPrice: int


class NewOrder(BaseModel):
    customer: str | None = None
    currency: str | None = None
    items: list[Item] | None = None


def json_body(request: Request) -> None:
    require_content_type(request, JSON)


def publish(event_type: str, data: dict) -> None:
    event = {"id": len(LOG) + 1, "type": event_type, "data": data}
    LOG.append(event)
    for queue in STREAMS:
        queue.put_nowait(event)


def frame(event: dict) -> str:
    """One SSE event: id, event name, data, then a blank line. retry tells the browser how soon to reconnect."""
    return f"id: {event['id']}\nevent: {event['type']}\nretry: 3000\ndata: {json.dumps(event['data'])}\n\n"


@app.post("/orders", status_code=201, dependencies=[Depends(json_body)])
async def create_order(body: NewOrder, response: Response):
    if not body.customer or not body.items:
        raise ApiError(422, "validation-error", "validation failed", {"#/items": "customer and at least one item are required"})
    order_id = f"ord_{next(IDS)}"
    total = sum(i.quantity * i.unitPrice for i in body.items)
    publish("order.created", {"id": order_id, "customer": body.customer, "total": total,
                              "at": datetime.now(UTC).isoformat()})
    response.headers["Location"] = f"/orders/{order_id}"
    return {"id": order_id, "customer": body.customer, "total": total, "status": "pending"}


@app.get("/orders/events")
async def order_events(request: Request):
    raw = request.headers.get("last-event-id", "")
    after = int(raw) if raw.isdigit() else 0

    async def stream():
        queue: asyncio.Queue = asyncio.Queue()
        STREAMS.add(queue)
        try:
            for event in LOG:  # first replay what the client missed...
                if event["id"] > after:
                    yield frame(event)
            while True:         # ...then follow live events
                try:
                    event = await asyncio.wait_for(queue.get(), HEARTBEAT_SECONDS)
                    if event["id"] > after:
                        yield frame(event)
                except TimeoutError:
                    yield ": keep-alive\n\n"  # a comment line: keeps proxies from closing an idle stream
        finally:
            STREAMS.discard(queue)

    # X-Accel-Buffering: no stops nginx from buffering the stream (Part 8); no-cache stops caches storing it.
    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
