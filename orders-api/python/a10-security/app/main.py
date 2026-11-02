"""A10: securing the orders API.

    request id  ->  authentication (middleware, 401)  ->  handler: authorisation (403, 404, 422)

Authentication runs for every path except the public ones, so a new endpoint is protected until
someone deliberately opens it up (deny by default). Authorisation lives in the handlers, because
only they know which order is being touched.

Run:  uv run uvicorn app.main:app --port 8000
"""

import re
import uuid
from dataclasses import asdict, dataclass, replace
from itertools import count
from typing import Annotated

from fastapi import Body, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from starlette.routing import Match

from . import auth
from .auth import Caller
from .http import ApiError, api_error_handler, problem, validation_handler

SAFE_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")
PUBLIC_PATHS = {"/token", "/.well-known/jwks.json"}

app = FastAPI(title="Orders API (A10)", docs_url=None, redoc_url=None, openapi_url=None)
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


@dataclass(frozen=True)
class Order:
    id: str
    customerId: str
    status: str
    total: str


ORDERS: dict[str, Order] = {
    "ord_1": Order("ord_1", "alice", "placed", "42.00"),
    "ord_2": Order("ord_2", "bob", "placed", "17.50"),
}
REFUND_IDS = count(1)


def caller(request: Request) -> Caller:
    return request.state.caller


def find(c: Caller, order_id: str, write: bool) -> Order:
    """Object-level authorisation. "Doesn't exist" and "isn't yours" give the same 404, word for word,
    so a caller can't probe for other people's order ids."""
    o = ORDERS.get(order_id)
    may_access = o is not None and (o.customerId == c.id or (c.role == "admin" if write else c.has("orders:read:any")))
    if not may_access:
        raise ApiError(404, "order-not-found", f"order {order_id} not found")
    return o


# ---------------------------------------------------------------- the token issuer (stand-in IdP)
@app.post("/token")
def token(body: Annotated[dict, Body()]):
    user = body.get("user")
    if user not in auth.USERS:
        raise ApiError(400, "unknown-user", f"no such user: {user}")
    return JSONResponse({"access_token": auth.issue_token(user), "token_type": "Bearer", "expires_in": auth.TOKEN_TTL},
                        headers={"Cache-Control": "no-store"})  # tokens must never sit in a cache


@app.get("/.well-known/jwks.json")
def jwks():
    return auth.jwks()


# ---------------------------------------------------------------- orders
# Every handler: 1. c.require(scope) -> 403    2. find(c, id, ...) -> 404    then the work.

@app.get("/orders")
def my_orders(request: Request):
    """Your own orders only. The filter comes from the token, never from a query parameter."""
    c = caller(request)
    c.require("orders:read")
    return {"data": [asdict(o) for o in sorted(ORDERS.values(), key=lambda o: o.id) if o.customerId == c.id]}


@app.get("/orders/{order_id}")
def get_order(order_id: str, request: Request):
    c = caller(request)
    c.require("orders:read")
    return asdict(find(c, order_id, write=False))


@app.patch("/orders/{order_id}")
def patch_order(order_id: str, request: Request, body: Annotated[dict, Body()]):
    """Customers may change exactly one thing: status, to "cancelled". Other fields (customerId, total)
    are refused, not silently applied: property-level authorisation, the defence against mass assignment."""
    c = caller(request)
    c.require("orders:write")
    o = find(c, order_id, write=True)
    read_only = {f"#/{k}": "cannot be changed" for k in sorted(body) if k != "status"}
    if read_only:
        raise ApiError(422, "read-only-field", "only status may be changed", read_only)
    if body.get("status") != "cancelled":
        raise ApiError(422, "validation-error", "status can only be set to cancelled", {"#/status": 'must be "cancelled"'})
    ORDERS[o.id] = replace(o, status="cancelled")
    return asdict(ORDERS[o.id])


@app.post("/orders/{order_id}/refunds", status_code=201)
def refund(order_id: str, request: Request):
    """Refunds are a staff action: the scope alone decides, and admins may refund any order."""
    c = caller(request)
    c.require("refunds:write")
    o = find(c, order_id, write=True)
    refund_id = f"ref_{next(REFUND_IDS)}"
    return JSONResponse({"id": refund_id, "orderId": o.id, "amount": o.total, "status": "pending"}, status_code=201,
                        headers={"Location": f"/orders/{o.id}/refunds/{refund_id}"})


@app.get("/admin/orders")
def all_orders(request: Request):
    """Everyone's orders: support, admins and back-office services only."""
    caller(request).require("orders:read:any")
    return {"data": [asdict(o) for o in sorted(ORDERS.values(), key=lambda o: o.id)]}


# ---------------------------------------------------------------- authentication (runs before routing)
@app.middleware("http")
async def authenticate_requests(request: Request, call_next):
    if request.url.path in PUBLIC_PATHS:
        return await call_next(request)
    try:
        request.state.caller = auth.authenticate(request.headers.get("authorization"), request.headers.get("x-api-key"))
    except auth.Unauthenticated as e:
        return problem(request, 401, e.type, e.detail, headers={"WWW-Authenticate": e.challenge})
    return await call_next(request)


# ---------------------------------------------------------------- request ids (outermost)
@app.middleware("http")
async def request_id(request: Request, call_next):
    incoming = request.headers.get("x-request-id", "")
    request.state.request_id = incoming if SAFE_ID.fullmatch(incoming) else str(uuid.uuid4())
    response = await call_next(request)
    response.headers["X-Request-Id"] = request.state.request_id
    return response
