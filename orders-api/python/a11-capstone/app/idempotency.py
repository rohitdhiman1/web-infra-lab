"""Stores the outcome of each Idempotency-Key so a retry gets the original response back.

    first request with a key    -> insert "in_progress", do the work, store the response
    same key, same request      -> replay the stored response (or 409 while still in progress)
    same key, different request -> 422: the client reused a key by mistake
"""

from dataclasses import dataclass

from .db import pool
from .http import ApiError


@dataclass
class Stored:
    status: int
    body: str
    location: str | None


def begin(key: str, fingerprint: str) -> Stored | None:
    """Returns None if this request is the first with the key (so the caller should do the work)."""
    with pool.connection() as conn:
        inserted = conn.execute(
            "INSERT INTO idempotency_keys (key, fingerprint, state) VALUES (%s, %s, 'in_progress') "
            "ON CONFLICT (key) DO NOTHING", (key, fingerprint)).rowcount
        if inserted == 1:
            return None
        row = conn.execute("SELECT * FROM idempotency_keys WHERE key = %s", (key,)).fetchone()
    if row["fingerprint"] != fingerprint:
        raise ApiError(422, "idempotency-key-reused",
                       "this Idempotency-Key was already used with a different request body or URL")
    if row["state"] == "in_progress":
        raise ApiError(409, "idempotency-key-in-progress",
                       "the original request is still being processed; retry shortly", headers={"Retry-After": "1"})
    return Stored(row["response_status"], row["response_body"], row["location"])


def complete(key: str, status: int, body: str, location: str | None) -> None:
    with pool.connection() as conn:
        conn.execute("UPDATE idempotency_keys SET state = 'completed', response_status = %s, response_body = %s, "
                     "location = %s WHERE key = %s", (status, body, location, key))


def abandon(key: str) -> None:
    """The work failed before anything changed: forget the key so the client can try again."""
    with pool.connection() as conn:
        conn.execute("DELETE FROM idempotency_keys WHERE key = %s", (key,))
