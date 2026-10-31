"""The same cases as the Java lab's GatewayConcernsTest."""

import time
import uuid

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def key() -> str:
    return f"key-{uuid.uuid4()}"  # a fresh bucket per test


def test_request_id_is_generated_when_absent():
    assert uuid.UUID(client.get("/orders", headers={"X-API-Key": key()}).headers["x-request-id"])


def test_valid_request_id_is_echoed_and_invalid_one_replaced():
    assert client.get("/orders", headers={"X-API-Key": key(), "X-Request-Id": "abc-123"}).headers["x-request-id"] == "abc-123"
    replaced = client.get("/orders", headers={"X-API-Key": key(), "X-Request-Id": "bad id; DROP TABLE"}).headers["x-request-id"]
    assert replaced != "bad id; DROP TABLE"


def test_problem_bodies_carry_the_request_id():
    res = client.get("/orders/ord_9", headers={"X-API-Key": key(), "X-Request-Id": "trace-me-42"})
    assert res.status_code == 404 and res.json()["requestId"] == "trace-me-42"


def test_burst_of_five_then_a_429_with_retry_after():
    k = key()
    for remaining in range(4, -1, -1):
        ok = client.get("/orders", headers={"X-API-Key": k})
        assert ok.status_code == 200 and ok.headers["x-ratelimit-remaining"] == str(remaining)
    limited = client.get("/orders", headers={"X-API-Key": k})
    assert limited.status_code == 429 and limited.headers["retry-after"] == "1"
    assert limited.headers["content-type"].startswith("application/problem+json")
    assert limited.json()["type"].endswith("rate-limited") and limited.json()["requestId"]


def test_limits_are_per_client():
    a = key()
    for _ in range(6):
        client.get("/orders", headers={"X-API-Key": a})
    assert client.get("/orders", headers={"X-API-Key": key()}).status_code == 200


def test_tokens_refill_over_time():
    k = key()
    for _ in range(6):
        client.get("/orders", headers={"X-API-Key": k})
    time.sleep(1.1)
    assert client.get("/orders", headers={"X-API-Key": k}).status_code == 200


def test_preflight_from_the_shop_is_allowed():
    res = client.options("/orders", headers={"Origin": "https://shop.example.com", "Access-Control-Request-Method": "POST",
                                             "Access-Control-Request-Headers": "content-type, x-api-key"})
    assert res.status_code == 200
    assert res.headers["access-control-allow-origin"] == "https://shop.example.com"
    assert "POST" in res.headers["access-control-allow-methods"]
    assert "x-api-key" in res.headers["access-control-allow-headers"].lower()
    assert res.headers["access-control-max-age"] == "600"


def test_preflight_from_another_site_gets_no_allow_origin():
    res = client.options("/orders", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
    assert "access-control-allow-origin" not in res.headers
    assert res.status_code == 400  # Starlette says 400; Spring says 403. Browsers only look at the missing header.


def test_cors_responses_expose_the_request_id():
    res = client.get("/orders", headers={"X-API-Key": key(), "Origin": "https://shop.example.com"})
    assert res.headers["access-control-allow-origin"] == "https://shop.example.com"
    assert "X-Request-Id" in res.headers["access-control-expose-headers"]
