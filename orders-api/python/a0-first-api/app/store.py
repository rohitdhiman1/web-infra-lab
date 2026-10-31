"""In-memory storage. Data disappears when the process stops; that's fine for A0–A2."""

import itertools
import threading
from datetime import UTC, datetime

from .models import NewOrder, Order


class OrderStore:
    def __init__(self) -> None:
        self._orders: dict[str, Order] = {}
        self._ids = itertools.count(1)
        self._lock = threading.Lock()

    def create(self, new: NewOrder) -> Order:
        with self._lock:
            order_id = f"ord_{next(self._ids)}"
            order = Order(
                id=order_id,
                customer=new.customer,
                currency=new.currency,
                items=new.items,
                total=sum(i.quantity * i.unit_price for i in new.items),
                status="pending",
                created_at=datetime.now(UTC),
            )
            self._orders[order_id] = order
            return order

    def find(self, order_id: str) -> Order | None:
        return self._orders.get(order_id)

    def all(self) -> list[Order]:
        return sorted(self._orders.values(), key=lambda o: int(o.id.removeprefix("ord_")))
