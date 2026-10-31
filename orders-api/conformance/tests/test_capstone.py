"""The A10 capstone spec as black-box tests. Set BASE_URL to the server under test."""

import os
import re
import uuid

import httpx2
import pytest

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
ORDER = {"customer": "ada@example.com", "currency": "EUR", "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}
MP = "application/merge-patch+json"
PROBLEM = "application/problem+json"


@pytest.fixture
def api():
    with httpx2.Client(base_url=BASE_URL, timeout=10, headers={"X-API-Key": f"conf-{uuid.uuid4()}"}) as c:
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
