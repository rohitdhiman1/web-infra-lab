"""The same cases as the Java lab's PaymentsApiTest, SlowGatewayTest and RetryingClientTest."""

import random
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import httpx2
import pytest

from app.gateway import gateway
from client.retrying import Policy, RetryingClient, safe_to_retry

NEW_ORDER = {"customer": "ada@example.com", "currency": "EUR",
             "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}


@pytest.fixture
def http(base_url):
    with httpx2.Client(base_url=base_url, timeout=5) as c:
        yield c


def create_order(http) -> str:
    return http.post("/orders", json=NEW_ORDER).headers["location"]


def pay(http, order: str, key: str | None, amount: int = 3000, timeout: float = 5):
    headers = {"Idempotency-Key": key} if key else {}
    return http.post(f"{order}/payments", json={"amount": amount}, headers=headers, timeout=timeout)


def new_key() -> str:
    return str(uuid.uuid4())


def test_orders_are_stored_in_postgres(http):
    res = http.get(create_order(http))
    assert res.status_code == 200 and res.json()["total"] == 3000 and res.json()["status"] == "pending"


def test_payment_without_key_is_400(http):
    res = pay(http, create_order(http), None)
    assert res.status_code == 400 and res.json()["type"].endswith("idempotency-key-missing")


def test_payment_creates_201_and_marks_order_paid(http):
    order = create_order(http)
    res = pay(http, order, new_key())
    assert res.status_code == 201 and res.headers["location"].startswith(f"{order}/payments/pay_")
    assert http.get(order).json()["status"] == "paid"


def test_same_key_replays_the_original_response(http):
    order, key = create_order(http), new_key()
    first, again = pay(http, order, key), pay(http, order, key)
    assert again.status_code == 201 and again.text == first.text
    assert again.headers["idempotent-replayed"] == "true"
    assert gateway.charges == 1
    assert len(http.get(f"{order}/payments").json()["data"]) == 1


def test_same_key_different_request_is_422(http):
    order, key = create_order(http), new_key()
    pay(http, order, key)
    res = pay(http, order, key, amount=2999)
    assert res.status_code == 422 and res.json()["type"].endswith("idempotency-key-reused")


def test_failed_request_frees_the_key(http):
    order, key = create_order(http), new_key()
    assert pay(http, order, key, amount=1).status_code == 422
    assert pay(http, order, key).status_code == 201


def test_second_payment_with_new_key_is_409(http):
    order = create_order(http)
    pay(http, order, new_key())
    res = pay(http, order, new_key())
    assert res.status_code == 409 and res.json()["type"].endswith("order-already-paid")
    assert gateway.charges == 1


# ---- the gateway takes 700 ms, longer than the client is willing to wait ----

def test_timed_out_payment_still_happens_and_retry_gets_the_result(http):
    gateway.delay_ms = 700
    order, key = create_order(http), new_key()
    with pytest.raises(httpx2.TimeoutException):
        pay(http, order, key, timeout=0.2)          # the client gives up; the server keeps going
    busy = pay(http, order, key)                    # still running
    assert busy.status_code == 409 and busy.headers["retry-after"] == "1"
    time.sleep(1)
    done = pay(http, order, key)                    # the original result, not a second charge
    assert done.status_code == 201 and done.headers["idempotent-replayed"] == "true"
    assert gateway.charges == 1


def test_retrying_client_recovers_on_its_own(http):
    gateway.delay_ms = 700
    order = create_order(http)
    client = RetryingClient(http, Policy(max_attempts=6, base=0.1, cap=2), rng=random.Random(42))
    request = http.build_request("POST", f"{order}/payments", json={"amount": 3000},
                                 headers={"Idempotency-Key": new_key()}, timeout=0.3)
    res = client.send(request)
    assert res.status_code == 201 and client.last_attempts > 1
    assert gateway.charges == 1


@pytest.mark.parametrize("same_key", [True, False])
def test_concurrent_payments_charge_once(http, same_key):
    gateway.delay_ms = 700
    order, key = create_order(http), new_key()
    with ThreadPoolExecutor(2) as pool:
        codes = sorted(pool.map(lambda _: pay(http, order, key if same_key else new_key()).status_code, range(2)))
    assert codes == [201, 409]
    assert gateway.charges == 1


# ---- the retry policy on its own ----

def test_backoff_grows_exponentially_up_to_the_cap():
    client = RetryingClient(None, Policy(base=0.1, cap=1), rng=random.Random(1))
    for attempt in range(1, 9):
        ceiling = min(1, 0.1 * 2 ** (attempt - 1))
        assert all(0 <= client.backoff(attempt, None) <= ceiling for _ in range(200))


def test_retry_after_wins():
    assert RetryingClient(None).backoff(1, "3") == 3


def test_post_is_only_retried_with_an_idempotency_key():
    plain = httpx2.Request("POST", "http://x/orders")
    keyed = httpx2.Request("POST", "http://x/orders", headers={"Idempotency-Key": "k"})
    assert not safe_to_retry(plain) and safe_to_retry(keyed) and safe_to_retry(httpx2.Request("GET", "http://x/"))


def test_wrong_method_lists_every_allowed_method(http):
    res = http.delete(f"{create_order(http)}/payments")
    assert res.status_code == 405 and {"GET", "POST"} <= {m.strip() for m in res.headers["allow"].split(",")}
