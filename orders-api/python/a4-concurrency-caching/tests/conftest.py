"""Runs the app in a real uvicorn server on a background thread. Needs Postgres: docker compose up -d"""

import socket
import threading
import time

import pytest
import uvicorn

from app import db
from app.main import app


@pytest.fixture(scope="session")
def base_url():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
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
        conn.execute("TRUNCATE orders RESTART IDENTITY")
