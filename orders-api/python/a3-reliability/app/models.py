"""Representations. Money is integer minor units (cents); JSON is camelCase like the Java lab."""

import re
from datetime import datetime

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class OrderItem(ApiModel):
    sku: str | None = None
    quantity: int | None = None
    unit_price: int | None = None


class OrderInput(ApiModel):
    customer: str | None = None
    currency: str | None = None
    items: list[OrderItem] | None = None

    def problems(self) -> dict[str, str]:
        p: dict[str, str] = {}
        if not self.customer or not self.customer.strip():
            p["#/customer"] = "is required"
        if not self.currency or not re.fullmatch(r"[A-Z]{3}", self.currency):
            p["#/currency"] = "must be a 3-letter ISO 4217 code, e.g. EUR"
        if not self.items:
            p["#/items"] = "must contain at least one item"
        else:
            for i, it in enumerate(self.items):
                if not it.sku or not it.sku.strip():
                    p[f"#/items/{i}/sku"] = "is required"
                if it.quantity is None or it.quantity < 1:
                    p[f"#/items/{i}/quantity"] = "must be at least 1"
                if it.unit_price is None or it.unit_price < 0:
                    p[f"#/items/{i}/unitPrice"] = "must be 0 or more"
        return p


class Order(ApiModel):
    id: str
    customer: str
    currency: str
    items: list[OrderItem]
    total: int
    status: str
    created_at: datetime


class Payment(ApiModel):
    id: str
    order_id: str
    amount: int
    currency: str
    charge_id: str
    created_at: datetime
