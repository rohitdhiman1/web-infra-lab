"""Representations the API sends and receives.

Money is an integer number of minor units (cents), never a float.
JSON uses camelCase (unitPrice, createdAt) to match the Java lab exactly.
"""

from datetime import datetime

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class OrderItem(ApiModel):
    sku: str
    quantity: int
    unit_price: int


class NewOrder(ApiModel):
    """What a client sends to create an order. The server decides id, total, status and createdAt."""

    customer: str
    currency: str
    items: list[OrderItem]


class Order(ApiModel):
    id: str
    customer: str
    currency: str
    items: list[OrderItem]
    total: int
    status: str
    created_at: datetime


class OrderList(ApiModel):
    # Wrap the array in an object: you can add paging fields later without breaking clients.
    data: list[Order]
