import json

from .db import pool
from .models import Order, OrderInput


def _order(row: dict) -> Order:
    return Order(id=f"ord_{row['id']}", customer=row["customer"], currency=row["currency"], items=row["items"],
                 total=row["total"], status=row["status"], created_at=row["created_at"], version=row["version"])


def _total(data: OrderInput) -> int:
    return sum(i.quantity * i.unit_price for i in data.items)


def _items(data: OrderInput) -> str:
    return json.dumps([i.model_dump(by_alias=True) for i in data.items])


def create_order(data: OrderInput) -> Order:
    with pool.connection() as conn:
        row = conn.execute(
            "INSERT INTO orders (customer, currency, items, total, status) "
            "VALUES (%s, %s, %s::jsonb, %s, 'pending') RETURNING *",
            (data.customer, data.currency, _items(data), _total(data))).fetchone()
    return _order(row)


def find_order(order_id: int) -> Order | None:
    with pool.connection() as conn:
        row = conn.execute("SELECT * FROM orders WHERE id = %s", (order_id,)).fetchone()
    return _order(row) if row else None


def update_if_version(order_id: int, expected_version: int, data: OrderInput) -> bool:
    """Optimistic locking: write only if the version is still the one the client read.
    Check and write happen in one statement, so two writers can't both succeed."""
    with pool.connection() as conn:
        cur = conn.execute(
            "UPDATE orders SET customer = %s, currency = %s, items = %s::jsonb, total = %s, status = %s, "
            "version = version + 1 WHERE id = %s AND version = %s",
            (data.customer, data.currency, _items(data), _total(data), data.status, order_id, expected_version))
        return cur.rowcount == 1
