"""Postgres from compose.yaml (throwaway lab credentials). The schema is shared with the Java lab."""

import os
from pathlib import Path

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

DSN = os.environ.get("DATABASE_URL", "postgresql://lab:lab@localhost:55432/orders")
pool = ConnectionPool(DSN, min_size=1, max_size=10, kwargs={"row_factory": dict_row, "autocommit": True}, open=False)


def init() -> None:
    pool.open(wait=True)
    with pool.connection() as conn:
        conn.execute(Path(__file__).parent.parent.joinpath("schema.sql").read_text())
