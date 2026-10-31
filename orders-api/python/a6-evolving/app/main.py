"""A6: two versions of the orders API over the same stored orders.

    /v1/orders  "customer" is an email string. Deprecated: every response carries Deprecation,
                Sunset and Link headers. It still works until the sunset date.
    /v2/orders  "customer" is an object {"email", "name"}: a type change, so a new version.

Both gained an optional "note" after launch: an additive change that needed no new version.

Run:  uv run uvicorn app.main:app --port 8000
"""

import itertools
import re
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel
from starlette.exceptions import HTTPException

from .http import JSON, ApiError, api_error_handler, require_accept_json, require_content_type, validation_handler

DEPRECATED_SINCE = "@1793491200"  # 2026-11-01T00:00:00Z, as RFC 9745 wants it: "@" + Unix seconds
SUNSET = "Fri, 30 Apr 2027 00:00:00 GMT"  # RFC 8594: an HTTP date


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class Item(ApiModel):
    sku: str | None = None
    quantity: int | None = None
    unit_price: int | None = None


class Stored(ApiModel):
    """How the server stores an order: separate from either version's representation."""

    id: str
    customer_email: str
    customer_name: str | None
    currency: str
    items: list[Item]
    note: str | None
    total: int
    status: str
    created_at: datetime


ORDERS: dict[str, Stored] = {}
IDS = itertools.count(1)


def create(email, name, currency, items, note) -> Stored:
    o = Stored(id=f"ord_{next(IDS)}", customer_email=email, customer_name=name, currency=currency, items=items,
               note=note, total=sum(i.quantity * i.unit_price for i in items), status="pending",
               created_at=datetime.now(UTC))
    ORDERS[o.id] = o
    return o


def find(order_id: str) -> Stored:
    if order_id not in ORDERS:
        raise ApiError(404, "order-not-found", f"order {order_id} not found")
    return ORDERS[order_id]


def check(email, email_pointer, currency, items) -> None:
    p = {}
    if not email or "@" not in email:
        p[email_pointer] = "must be an email address"
    if not currency or not re.fullmatch(r"[A-Z]{3}", currency):
        p["#/currency"] = "must be a 3-letter ISO 4217 code"
    if not items:
        p["#/items"] = "must contain at least one item"
    else:
        for i, it in enumerate(items):
            if not it.sku or not it.quantity or it.quantity < 1 or it.unit_price is None or it.unit_price < 0:
                p[f"#/items/{i}"] = "needs a sku, a quantity of at least 1 and a unitPrice of 0 or more"
    if p:
        raise ApiError(422, "validation-error", "validation failed", p)


def json_body(request: Request) -> None:
    require_content_type(request, JSON)


# ---------------------------------------------------------------- v1
class NewOrderV1(ApiModel):
    customer: str | None = None
    currency: str | None = None
    items: list[Item] | None = None
    note: str | None = None  # added after launch: optional, so old clients never notice


class OrderV1(ApiModel):
    id: str
    customer: str
    currency: str
    items: list[Item]
    note: str | None
    total: int
    status: str
    created_at: datetime


def v1_view(o: Stored) -> OrderV1:
    return OrderV1(id=o.id, customer=o.customer_email, currency=o.currency, items=o.items, note=o.note,
                   total=o.total, status=o.status, created_at=o.created_at)


v1 = APIRouter(prefix="/v1/orders")


@v1.post("", status_code=201, response_model=OrderV1, dependencies=[Depends(json_body)])
def create_v1(body: NewOrderV1, response: Response):
    check(body.customer, "#/customer", body.currency, body.items)
    o = create(body.customer, None, body.currency, body.items, body.note)
    response.headers["Location"] = f"/v1/orders/{o.id}"
    return v1_view(o)


@v1.get("/{order_id}", response_model=OrderV1)
def get_v1(order_id: str):
    return v1_view(find(order_id))


# ---------------------------------------------------------------- v2
class Customer(ApiModel):
    email: str | None = None
    name: str | None = None


class NewOrderV2(ApiModel):
    customer: Customer | None = None
    currency: str | None = None
    items: list[Item] | None = None
    note: str | None = None


class OrderV2(ApiModel):
    id: str
    customer: Customer
    currency: str
    items: list[Item]
    note: str | None
    total: int
    status: str
    created_at: datetime


def v2_view(o: Stored) -> OrderV2:
    return OrderV2(id=o.id, customer=Customer(email=o.customer_email, name=o.customer_name), currency=o.currency,
                   items=o.items, note=o.note, total=o.total, status=o.status, created_at=o.created_at)


v2 = APIRouter(prefix="/v2/orders")


@v2.post("", status_code=201, response_model=OrderV2, dependencies=[Depends(json_body)])
def create_v2(body: NewOrderV2, response: Response):
    email = body.customer.email if body.customer else None
    check(email, "#/customer/email", body.currency, body.items)
    o = create(email, body.customer.name, body.currency, body.items, body.note)
    response.headers["Location"] = f"/v2/orders/{o.id}"
    return v2_view(o)


@v2.get("/{order_id}", response_model=OrderV2)
def get_v2(order_id: str):
    return v2_view(find(order_id))


# ---------------------------------------------------------------- app
app = FastAPI(title="Orders API (A6)", dependencies=[Depends(require_accept_json)])
app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)
app.include_router(v1)
app.include_router(v2)


@app.exception_handler(HTTPException)
async def http_error(request: Request, e: HTTPException):
    return await api_error_handler(request, ApiError(e.status_code, None, str(e.detail), headers=dict(e.headers or {})))


@app.middleware("http")
async def deprecation_headers(request: Request, call_next):
    """Every v1 response says, in standard headers, that v1 is going away and what replaces it."""
    response = await call_next(request)
    if request.url.path.startswith("/v1/"):
        response.headers["Deprecation"] = DEPRECATED_SINCE
        response.headers["Sunset"] = SUNSET
        response.headers.append("Link", '<https://api.example.com/docs/migrating-to-v2>; rel="deprecation"')
        response.headers.append("Link", '</v2/orders>; rel="successor-version"')
    return response
