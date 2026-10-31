"""Tests run the app in a real uvicorn server on a background thread, so the client can time out
for real while the server keeps working. Needs Postgres: docker compose up -d"""

import socket
import threading
import time

import pytest
import uvicorn

from app import db
from app.gateway import gateway
from app.main import app


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def base_url():
    port = free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    while not server.started:
        time.sleep(0.05)
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(5)


@pytest.fixture(autouse=True)
def clean(base_url):
    with db.pool.connection() as conn:
        conn.execute("TRUNCATE orders, payments, idempotency_keys RESTART IDENTITY CASCADE")
    gateway.reset()
    gateway.delay_ms = 0
    yield
