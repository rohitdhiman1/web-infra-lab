import json

from .db import pool
from .models import Order, OrderInput, Payment


def _order(row: dict) -> Order:
    return Order(id=f"ord_{row['id']}", customer=row["customer"], currency=row["currency"], items=row["items"],
                 total=row["total"], status=row["status"], created_at=row["created_at"])


def create_order(data: OrderInput) -> Order:
    total = sum(i.quantity * i.unit_price for i in data.items)
    items = json.dumps([i.model_dump(by_alias=True) for i in data.items])
    with pool.connection() as conn:
        row = conn.execute(
            "INSERT INTO orders (customer, currency, items, total, status) "
            "VALUES (%s, %s, %s::jsonb, %s, 'pending') RETURNING *",
            (data.customer, data.currency, items, total)).fetchone()
    return _order(row)


def find_order(order_id: int) -> Order | None:
    with pool.connection() as conn:
        row = conn.execute("SELECT * FROM orders WHERE id = %s", (order_id,)).fetchone()
    return _order(row) if row else None


def claim_for_payment(order_id: int) -> bool:
    """Only one request can move an order from pending to paying: check and update in one statement."""
    with pool.connection() as conn:
        cur = conn.execute("UPDATE orders SET status = 'paying' WHERE id = %s AND status = 'pending'", (order_id,))
        return cur.rowcount == 1


def release_claim(order_id: int) -> None:
    with pool.connection() as conn:
        conn.execute("UPDATE orders SET status = 'pending' WHERE id = %s AND status = 'paying'", (order_id,))


def record_payment(order_id: int, amount: int, currency: str, charge_id: str) -> Payment:
    """One transaction: the order is marked paid if and only if the payment row exists."""
    with pool.connection() as conn, conn.transaction():
        conn.execute("UPDATE orders SET status = 'paid' WHERE id = %s", (order_id,))
        row = conn.execute(
            "INSERT INTO payments (order_id, amount, currency, charge_id) VALUES (%s, %s, %s, %s) "
            "RETURNING id, created_at", (order_id, amount, currency, charge_id)).fetchone()
    return Payment(id=f"pay_{row['id']}", order_id=f"ord_{order_id}", amount=amount, currency=currency,
                   charge_id=charge_id, created_at=row["created_at"])


def payments(order_id: int) -> list[Payment]:
    with pool.connection() as conn:
        rows = conn.execute("SELECT * FROM payments WHERE order_id = %s ORDER BY id", (order_id,)).fetchall()
    return [Payment(id=f"pay_{r['id']}", order_id=f"ord_{order_id}", amount=r["amount"], currency=r["currency"],
                    charge_id=r["charge_id"], created_at=r["created_at"]) for r in rows]
