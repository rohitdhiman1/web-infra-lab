import json

from .db import pool
from .models import Order, OrderInput, Payment


def _order(row: dict) -> Order:
    return Order(id=f"ord_{row['id']}", customer=row["customer"], currency=row["currency"], items=row["items"],
                 total=row["total"], status=row["status"], created_at=row["created_at"], version=row["version"],
                 owner=row["owner"])


def _total(data: OrderInput) -> int:
    return sum(i.quantity * i.unit_price for i in data.items)


def _items(data: OrderInput) -> str:
    return json.dumps([i.model_dump(by_alias=True) for i in data.items])


def create_order(data: OrderInput, owner: str) -> Order:
    """The owner comes from the caller's token, never from the request body."""
    with pool.connection() as conn:
        row = conn.execute(
            "INSERT INTO orders (customer, currency, items, total, status, owner) "
            "VALUES (%s, %s, %s::jsonb, %s, 'pending', %s) RETURNING *",
            (data.customer, data.currency, _items(data), _total(data), owner)).fetchone()
    return _order(row)


def find_order(order_id: int) -> Order | None:
    with pool.connection() as conn:
        row = conn.execute("SELECT * FROM orders WHERE id = %s", (order_id,)).fetchone()
    return _order(row) if row else None


def find_visible(order_id: int, caller: str, any_owner: bool) -> Order | None:
    """A10, object-level authorisation in the query itself: an order that isn't yours comes back
    exactly like one that doesn't exist. any_owner is True for staff who may see every order."""
    with pool.connection() as conn:
        row = conn.execute("SELECT * FROM orders WHERE id = %s AND (owner = %s OR %s)",
                           (order_id, caller, any_owner)).fetchone()
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


def page_after(owner: str | None, before_id: int | None, status: str | None, limit: int) -> list[Order]:
    """Keyset (cursor) pagination: "the next `limit` orders older than the last one you saw".
    WHERE id < %s uses the primary-key index, so page 1,000 costs the same as page 1, and rows
    inserted or deleted elsewhere can't shift what you see next."""
    with pool.connection() as conn:
        rows = conn.execute(
            "SELECT * FROM orders WHERE (%(u)s::text IS NULL OR owner = %(u)s) "
            "AND (%(b)s::bigint IS NULL OR id < %(b)s) "
            "AND (%(s)s::text IS NULL OR status = %(s)s) ORDER BY id DESC LIMIT %(n)s",
            {"u": owner, "b": before_id, "s": status, "n": limit}).fetchall()
    return [_order(r) for r in rows]


def page_at_offset(owner: str | None, offset: int, status: str | None, limit: int) -> list[Order]:
    """Offset pagination, for comparison: the database still reads and throws away `offset` rows."""
    with pool.connection() as conn:
        rows = conn.execute(
            "SELECT * FROM orders WHERE (%(u)s::text IS NULL OR owner = %(u)s) "
            "AND (%(s)s::text IS NULL OR status = %(s)s) "
            "ORDER BY id DESC LIMIT %(n)s OFFSET %(o)s",
            {"u": owner, "s": status, "n": limit, "o": offset}).fetchall()
    return [_order(r) for r in rows]


def claim_for_payment(order_id: int) -> bool:
    """Only one request can move an order from pending to paying. Bumps the version: the ETag changes."""
    with pool.connection() as conn:
        cur = conn.execute("UPDATE orders SET status = 'paying', version = version + 1 "
                           "WHERE id = %s AND status = 'pending'", (order_id,))
        return cur.rowcount == 1


def release_claim(order_id: int) -> None:
    with pool.connection() as conn:
        conn.execute("UPDATE orders SET status = 'pending', version = version + 1 "
                     "WHERE id = %s AND status = 'paying'", (order_id,))


def record_payment(order_id: int, amount: int, currency: str, charge_id: str) -> Payment:
    """One transaction: the order is marked paid if and only if the payment row exists."""
    with pool.connection() as conn, conn.transaction():
        conn.execute("UPDATE orders SET status = 'paid', version = version + 1 WHERE id = %s", (order_id,))
        row = conn.execute("INSERT INTO payments (order_id, amount, currency, charge_id) VALUES (%s, %s, %s, %s) "
                           "RETURNING id, created_at", (order_id, amount, currency, charge_id)).fetchone()
    return Payment(id=f"pay_{row['id']}", order_id=f"ord_{order_id}", amount=amount, currency=currency,
                   charge_id=charge_id, created_at=row["created_at"])


def payments(order_id: int) -> list[Payment]:
    with pool.connection() as conn:
        rows = conn.execute("SELECT * FROM payments WHERE order_id = %s ORDER BY id", (order_id,)).fetchall()
    return [Payment(id=f"pay_{r['id']}", order_id=f"ord_{order_id}", amount=r["amount"], currency=r["currency"],
                    charge_id=r["charge_id"], created_at=r["created_at"]) for r in rows]
