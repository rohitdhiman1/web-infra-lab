"""The same cases as the Java lab's SecurityTest."""

import base64
import json
import time

import jwt
import pytest
from fastapi.testclient import TestClient

from app import auth, main
from app.main import Order, app

WAREHOUSE_KEY = "wh_test_4f9c2a7d1e8b6035"
client = TestClient(app)


@pytest.fixture(autouse=True)
def fresh_orders(monkeypatch):
    monkeypatch.setattr(main, "ORDERS", {
        "ord_1": Order("ord_1", "alice", "placed", "42.00"),
        "ord_2": Order("ord_2", "bob", "placed", "17.50"),
    })
    monkeypatch.setattr(main, "REFUND_IDS", iter(range(1, 1000)))


def token(user: str) -> str:
    res = client.post("/token", json={"user": user})
    assert res.status_code == 200
    return res.json()["access_token"]


def as_(user: str, method: str, path: str, body: dict | None = None):
    return client.request(method, path, json=body, headers={"Authorization": f"Bearer {token(user)}"})


def bearer(t: str, path: str = "/orders/ord_1"):
    return client.get(path, headers={"Authorization": f"Bearer {t}"})


def forged(iss: str, aud: str, exp: int) -> str:
    """Signed with the real key, with claims the issuer would never produce."""
    claims = {"iss": iss, "aud": aud, "sub": "alice", "iat": exp - 300, "exp": exp, "scope": "orders:read orders:write"}
    return jwt.encode(claims, auth.PRIVATE_KEY, algorithm="ES256")


def b64(s: str) -> str:
    return base64.urlsafe_b64encode(s.encode()).rstrip(b"=").decode()


# ---- authentication: 401 ----

def test_no_credential_is_401_with_a_challenge():
    res = client.get("/orders/ord_1")
    assert res.status_code == 401
    assert res.headers["www-authenticate"] == 'Bearer realm="orders-api"'
    assert res.headers["content-type"].startswith("application/problem+json")
    assert res.json()["type"].endswith("/unauthenticated")


def test_unknown_paths_are_401_too_so_they_reveal_nothing():
    assert client.get("/no-such-thing").status_code == 401


def test_token_response_is_never_cached():
    res = client.post("/token", json={"user": "alice"})
    assert "no-store" in res.headers["cache-control"] and res.json()["token_type"] == "Bearer"
    assert client.post("/token", json={"user": "mallory"}).status_code == 400


def test_expired_token_is_401_invalid_token():
    res = bearer(forged(auth.ISSUER, auth.AUDIENCE, int(time.time()) - 600))
    assert res.status_code == 401
    assert 'error="invalid_token"' in res.headers["www-authenticate"]
    assert res.json()["type"].endswith("/invalid-token")


def test_wrong_issuer_or_audience_is_401():
    later = int(time.time()) + 300
    assert bearer(forged("https://evil.test", auth.AUDIENCE, later)).status_code == 401
    assert bearer(forged(auth.ISSUER, "billing-api", later)).status_code == 401


def test_tampered_or_unsigned_token_is_401():
    header, _, signature = token("bob").split(".")
    # Swap in a payload claiming to be alice, keeping bob's signature.
    payload = b64(json.dumps({"iss": auth.ISSUER, "aud": auth.AUDIENCE, "sub": "alice", "scope": "orders:read",
                              "exp": int(time.time()) + 300}))
    assert bearer(f"{header}.{payload}.{signature}").status_code == 401
    assert bearer(f"{b64(json.dumps({'alg': 'none', 'typ': 'JWT'}))}.{payload}.").status_code == 401


def test_unknown_api_key_is_401():
    res = client.get("/admin/orders", headers={"X-API-Key": "wh_test_guessed"})
    assert res.status_code == 401 and res.headers["www-authenticate"] == 'ApiKey realm="orders-api"'


def test_jwks_publishes_only_the_public_key():
    key = client.get("/.well-known/jwks.json").json()["keys"][0]
    assert key["kty"] == "EC" and "d" not in key  # "d" is the private part of an EC key


# ---- function-level authorisation: 403 ----

def test_missing_scope_is_403_with_the_scope_named():
    res = as_("alice", "POST", "/orders/ord_1/refunds")
    assert res.status_code == 403
    assert res.headers["www-authenticate"] == 'Bearer error="insufficient_scope", scope="refunds:write"'
    assert as_("bob", "GET", "/admin/orders").status_code == 403


# ---- object-level authorisation: 404 ----

def test_bob_cannot_see_or_cancel_alices_order():
    read, missing = as_("bob", "GET", "/orders/ord_1"), as_("bob", "GET", "/orders/ord_999")
    assert read.status_code == 404
    # Identical to an order that doesn't exist, so ids can't be probed.
    assert read.json()["detail"] == "order ord_1 not found" and missing.json()["type"] == read.json()["type"]
    assert as_("bob", "PATCH", "/orders/ord_1", {"status": "cancelled"}).status_code == 404


def test_alice_reads_and_cancels_her_own_order():
    assert as_("alice", "GET", "/orders/ord_1").json()["customerId"] == "alice"
    assert len(as_("alice", "GET", "/orders").json()["data"]) == 1
    res = as_("alice", "PATCH", "/orders/ord_1", {"status": "cancelled"})
    assert res.status_code == 200 and res.json()["status"] == "cancelled"


def test_support_reads_any_order_but_cannot_cancel_it():
    assert as_("sam", "GET", "/orders/ord_1").status_code == 200
    assert as_("sam", "GET", "/admin/orders").status_code == 200
    assert as_("sam", "PATCH", "/orders/ord_1", {"status": "cancelled"}).status_code == 403


def test_admin_refunds_any_order():
    res = as_("ana", "POST", "/orders/ord_1/refunds")
    assert res.status_code == 201 and res.headers["location"] == "/orders/ord_1/refunds/ref_1"
    assert as_("ana", "POST", "/orders/ord_999/refunds").status_code == 404


# ---- property-level authorisation: 422 ----

def test_owner_cannot_change_read_only_fields():
    res = as_("alice", "PATCH", "/orders/ord_1", {"status": "cancelled", "total": "0.01"})
    assert res.status_code == 422 and res.json()["errors"][0]["pointer"] == "#/total"
    assert as_("alice", "GET", "/orders/ord_1").json()["status"] == "placed"


# ---- service callers with API keys ----

def test_warehouse_service_reads_all_orders_with_its_key():
    res = client.get("/admin/orders", headers={"X-API-Key": WAREHOUSE_KEY})
    assert res.status_code == 200 and len(res.json()["data"]) == 2
    assert client.patch("/orders/ord_1", json={"status": "cancelled"}, headers={"X-API-Key": WAREHOUSE_KEY}).status_code == 403


def test_405_lists_every_method_of_the_resource():
    res = as_("alice", "DELETE", "/orders/ord_1")
    assert res.status_code == 405 and res.headers["allow"] == "GET, PATCH"
