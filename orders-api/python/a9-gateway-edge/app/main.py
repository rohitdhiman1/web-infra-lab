"""A9: what sits around the endpoints. Request ids, per-client rate limits, CORS.

Middleware order matters. The last one added runs first (outermost):
    RequestId  ->  CORS  ->  RateLimit  ->  the endpoint
so even a 429 or a CORS rejection carries a request id, and preflights never reach the limiter.

Run:  uv run uvicorn app.main:app --port 8000
"""

import math
import os
import re
import threading
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException
from starlette.middleware.cors import CORSMiddleware

from .http import TYPES, ApiError, api_error_handler, problem, validation_handler

CAPACITY = int(os.environ.get("RATELIMIT_CAPACITY", "5"))
RATE = float(os.environ.get("RATELIMIT_REFILL_PER_SECOND", "1"))
SAFE_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")

app = FastAPI(title="Orders API (A9)")
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    return await api_error_handler(request, ApiError(e.status_code, None, str(e.detail), headers=dict(e.headers or {})))


@app.get("/orders")
def list_orders():
    return {"data": []}


@app.get("/orders/{order_id}")
def get_order(order_id: str):
    raise ApiError(404, "order-not-found", f"order {order_id} not found")


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


# ---------------------------------------------------------------- CORS
# Only the shop's own web app may call this API from a browser. Browsers enforce CORS:
# it protects users from other websites, not the API from curl or servers.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://shop.example.com"],
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-API-Key", "X-Request-Id"],
    expose_headers=["X-Request-Id", "X-RateLimit-Remaining", "Retry-After"],  # readable by browser JS
    max_age=600,
)


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
