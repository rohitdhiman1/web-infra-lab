"""The same cases as the Java lab's EventStreamTest, against a real uvicorn server."""

import socket
import threading
import time

import httpx2
import pytest
import uvicorn

from app import main

ORDER = {"currency": "EUR", "items": [{"sku": "BOOK-42", "quantity": 1, "unitPrice": 1500}]}


@pytest.fixture(scope="module")
def base_url():
    main.HEARTBEAT_SECONDS = 0.2
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(main.app, host="127.0.0.1", port=port, log_level="warning",
                                           timeout_graceful_shutdown=1))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    while not server.started:
        time.sleep(0.05)
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(5)


@pytest.fixture(autouse=True)
def fresh_log():
    main.LOG.clear()


def create(base_url, customer):
    httpx2.post(f"{base_url}/orders", json={"customer": customer} | ORDER)


def read_events(response, n):
    events, current = [], []
    for line in response.iter_lines():
        if line == "":
            if any(x.startswith("id:") for x in current):
                events.append("\n".join(current))
            current = []
            if len(events) == n:
                break
        else:
            current.append(line)
    return events


def test_stream_delivers_events_as_they_happen(base_url):
    with httpx2.stream("GET", f"{base_url}/orders/events", timeout=5) as res:
        assert res.headers["content-type"].startswith("text/event-stream")
        create(base_url, "ada@example.com")
        create(base_url, "bob@example.com")
        events = read_events(res, 2)
    assert "id: 1" in events[0] and "event: order.created" in events[0] and "ada@example.com" in events[0]
    assert "id: 2" in events[1] and "bob@example.com" in events[1]


def test_reconnect_with_last_event_id_resumes(base_url):
    for who in ("ada", "bob", "cy"):
        create(base_url, f"{who}@example.com")
    with httpx2.stream("GET", f"{base_url}/orders/events", headers={"Last-Event-ID": "1"}, timeout=5) as res:
        events = read_events(res, 2)
    assert "id: 2" in events[0] and "bob@example.com" in events[0]
    assert "id: 3" in events[1] and "cy@example.com" in events[1]


def test_idle_streams_get_heartbeats(base_url):
    with httpx2.stream("GET", f"{base_url}/orders/events", timeout=5) as res:
        assert any(line.startswith(":") for line, _ in zip(res.iter_lines(), range(10)))
