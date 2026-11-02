"""The A11 capstone spec as black-box tests. Set BASE_URL to the server under test."""

import os
import re
import uuid

import httpx2
import pytest

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
ORDER = {"customer": "ada@example.com", "currency": "EUR", "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}
MP = "application/merge-patch+json"
PROBLEM = "application/problem+json"
# The warehouse service's API key: a throwaway lab value, the same in every implementation.
WAREHOUSE_KEY = "wh_test_4f9c2a7d1e8b6035"


def token(user: str) -> str:
    """A fresh token from the implementation's stand-in identity provider. Each token is a fresh
    rate-limit bucket, so the suite doesn't trip the limiter except in the test that means to."""
    res = httpx2.post(f"{BASE_URL}/token", json={"user": user}, timeout=10)
    assert res.status_code == 200, res.text
    return res.json()["access_token"]


def client_as(user: str) -> httpx2.Client:
    return httpx2.Client(base_url=BASE_URL, timeout=10, headers={"Authorization": f"Bearer {token(user)}"})


@pytest.fixture
def api():
    """Alice: a customer with orders:read and orders:write."""
    with client_as("alice") as c:
        yield c


@pytest.fixture
def anon():
    with httpx2.Client(base_url=BASE_URL, timeout=10) as c:
        yield c


def create(api) -> httpx2.Response:
    res = api.post("/orders", json=ORDER)
    assert res.status_code == 201, res.text
    return res


def assert_problem(res, status, type_suffix=None):
    assert res.status_code == status, res.text
    assert res.headers["content-type"].startswith(PROBLEM)
    body = res.json()
    assert body["status"] == status and body["title"] and body["instance"]
    if type_suffix:
        assert body["type"].endswith("/" + type_suffix)
    return body


# ---- A1 / A2: semantics and errors ----

def test_create_returns_201_location_etag(api):
    res = create(api)
    assert re.fullmatch(r"/orders/ord_\d+", res.headers["location"])
    assert res.headers["etag"] == '"1"'
    assert res.json()["total"] == 3000 and res.json()["status"] == "pending"


def test_validation_errors_are_problems_with_pointers(api):
    body = assert_problem(api.post("/orders", json={"customer": "", "currency": "euro", "items": []}), 422, "validation-error")
    assert {"#/customer", "#/currency", "#/items"} <= {e["pointer"] for e in body["errors"]}


def test_missing_order_is_a_404_problem(api):
    assert_problem(api.get("/orders/ord_999999999"), 404, "order-not-found")


def test_wrong_method_is_405_with_allow(api):
    res = api.delete("/orders")
    assert res.status_code == 405 and "GET" in res.headers["allow"]


# ---- A4: conditional requests ----

def test_conditional_get_and_write(api):
    path = create(api).headers["location"]
    assert api.get(path, headers={"If-None-Match": '"1"'}).status_code == 304
    assert_problem(api.patch(path, content='{"customer": "x@example.com"}', headers={"Content-Type": MP}), 428,
                   "precondition-required")
    ok = api.patch(path, content='{"customer": "x@example.com"}', headers={"Content-Type": MP, "If-Match": '"1"'})
    assert ok.status_code == 200 and ok.headers["etag"] == '"2"'
    assert_problem(api.patch(path, content='{"customer": "y@example.com"}', headers={"Content-Type": MP, "If-Match": '"1"'}),
                   412, "precondition-failed")


# ---- A5: pagination ----

def test_cursor_pagination_is_stable(api):
    ids = [create(api).json()["id"] for _ in range(3)]
    page1 = api.get("/orders", params={"limit": 2})
    assert [o["id"] for o in page1.json()["data"]] == [ids[2], ids[1]]
    assert page1.headers["link"] == f'<{page1.json()["next"]}>; rel="next"'
    create(api)  # a new order arrives between pages
    page2 = api.get(page1.json()["next"])
    assert page2.json()["data"][0]["id"] == ids[0]


def test_bad_page_size_is_400(api):
    assert_problem(api.get("/orders", params={"limit": 1000}), 400, "invalid-parameter")


# ---- A3: idempotent payments ----

def test_payments_are_idempotent(api):
    created = create(api)
    path, etag = created.headers["location"], created.headers["etag"]
    assert_problem(api.post(f"{path}/payments", json={"amount": 3000}), 400, "idempotency-key-missing")
    key = str(uuid.uuid4())
    first = api.post(f"{path}/payments", json={"amount": 3000}, headers={"Idempotency-Key": key})
    assert first.status_code == 201
    again = api.post(f"{path}/payments", json={"amount": 3000}, headers={"Idempotency-Key": key})
    assert again.status_code == 201 and again.text == first.text and again.headers["idempotent-replayed"] == "true"
    assert_problem(api.post(f"{path}/payments", json={"amount": 2999}, headers={"Idempotency-Key": key}), 422,
                   "idempotency-key-reused")
    assert_problem(api.post(f"{path}/payments", json={"amount": 3000}, headers={"Idempotency-Key": str(uuid.uuid4())}),
                   409, "order-not-payable")
    assert len(api.get(f"{path}/payments").json()["data"]) == 1
    order = api.get(path)
    assert order.json()["status"] == "paid" and order.headers["etag"] != etag  # paying changed the version


def test_paid_orders_cannot_be_edited(api):
    path = create(api).headers["location"]
    api.post(f"{path}/payments", json={"amount": 3000}, headers={"Idempotency-Key": str(uuid.uuid4())})
    etag = api.get(path).headers["etag"]
    assert_problem(api.patch(path, content='{"customer": "x@example.com"}', headers={"Content-Type": MP, "If-Match": etag}),
                   409, "order-locked")


# ---- A9: request ids and rate limits ----

def test_request_ids(api):
    assert api.get("/orders", headers={"X-Request-Id": "conf-abc-1"}).headers["x-request-id"] == "conf-abc-1"
    body = assert_problem(api.get("/orders/ord_999999999", headers={"X-Request-Id": "conf-abc-2"}), 404)
    assert body["requestId"] == "conf-abc-2"


def test_rate_limit_eventually_says_429(api):
    for _ in range(300):
        res = api.get("/orders", params={"limit": 1})
        if res.status_code == 429:
            break
    body = assert_problem(res, 429, "rate-limited")
    assert int(res.headers["retry-after"]) >= 1 and body["requestId"]


# ---- A10: authentication and authorisation ----

def test_no_credential_is_401_with_a_challenge(anon):
    res = anon.get("/orders")
    assert_problem(res, 401, "unauthenticated")
    assert res.headers["www-authenticate"].startswith("Bearer")


def test_bad_credentials_are_401(anon):
    res = anon.get("/orders", headers={"Authorization": "Bearer not.a.token"})
    assert_problem(res, 401, "invalid-token")
    assert 'error="invalid_token"' in res.headers["www-authenticate"]
    assert_problem(anon.get("/orders", headers={"X-API-Key": "wh_test_guessed"}), 401, "invalid-token")


def test_missing_scope_is_403(api):
    path = create(api).headers["location"]
    with client_as("sam") as support:  # support: orders:read and orders:read:any, no orders:write
        assert_problem(support.post("/orders", json=ORDER), 403, "insufficient-scope")
        res = support.patch(path, content='{"status": "cancelled"}', headers={"Content-Type": MP, "If-Match": '"1"'})
        assert_problem(res, 403, "insufficient-scope")
        assert 'scope="orders:write"' in res.headers["www-authenticate"]


def test_other_customers_orders_are_404(api):
    created = create(api)
    path, oid = created.headers["location"], created.json()["id"]
    with client_as("bob") as bob:
        # Exactly what a missing order looks like, so Bob can't tell Alice's order exists.
        assert_problem(bob.get(path), 404, "order-not-found")
        assert_problem(bob.patch(path, content='{"status": "cancelled"}', headers={"Content-Type": MP, "If-Match": '"1"'}),
                       404, "order-not-found")
        assert_problem(bob.post(f"{path}/payments", json={"amount": 3000}, headers={"Idempotency-Key": str(uuid.uuid4())}),
                       404, "order-not-found")
        assert_problem(bob.get(f"{path}/payments"), 404, "order-not-found")
        assert oid not in [o["id"] for o in bob.get("/orders", params={"limit": 100}).json()["data"]]
    assert api.get(path).json()["status"] == "pending"  # untouched


def test_staff_and_services_may_read_any_order(api):
    path = create(api).headers["location"]
    with client_as("sam") as support:
        assert support.get(path).status_code == 200
    assert httpx2.get(f"{BASE_URL}{path}", headers={"X-API-Key": WAREHOUSE_KEY}, timeout=10).status_code == 200

