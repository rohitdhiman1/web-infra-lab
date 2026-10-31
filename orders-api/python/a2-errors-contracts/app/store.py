"""In-memory storage. Data disappears when the process stops; that's fine for A0–A2."""

import itertools
import threading
from datetime import UTC, datetime

from .models import Order, OrderInput


def build(order_id: str, data: OrderInput, status: str, created_at: datetime) -> Order:
    total = sum(i.quantity * i.unit_price for i in data.items)
    return Order(id=order_id, customer=data.customer, currency=data.currency, items=data.items,
                 total=total, status=status, created_at=created_at)


class OrderStore:
    def __init__(self) -> None:
        self._orders: dict[str, Order] = {}
        self._ids = itertools.count(1)
        self._lock = threading.Lock()

    def create(self, data: OrderInput) -> Order:
        with self._lock:
            order = build(f"ord_{next(self._ids)}", data, "pending", datetime.now(UTC))
            self._orders[order.id] = order
            return order

    def find(self, order_id: str) -> Order | None:
        return self._orders.get(order_id)

    def save(self, order: Order) -> None:
        self._orders[order.id] = order

    def delete(self, order_id: str) -> bool:
        return self._orders.pop(order_id, None) is not None

    def all(self) -> list[Order]:
        return sorted(self._orders.values(), key=lambda o: int(o.id.removeprefix("ord_")))
