"""The same cases as the Java lab's ConditionalRequestsTest."""

from concurrent.futures import ThreadPoolExecutor

import httpx2
import pytest

NEW_ORDER = {"customer": "ada@example.com", "currency": "EUR",
             "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}
MP = {"Content-Type": "application/merge-patch+json"}


@pytest.fixture
def http(base_url):
    with httpx2.Client(base_url=base_url, timeout=5) as c:
        yield c


def create(http) -> str:
    return http.post("/orders", json=NEW_ORDER).headers["location"]


def patch(http, path, body, **headers):
    return http.patch(path, content=body, headers=MP | {k.replace("_", "-"): v for k, v in headers.items()})


def test_responses_carry_an_etag_and_cache_control(http):
    created = http.post("/orders", json=NEW_ORDER)
    assert created.headers["etag"] == '"1"'
    read = http.get(created.headers["location"])
    assert read.headers["etag"] == '"1"'
    assert "no-cache" in read.headers["cache-control"] and "private" in read.headers["cache-control"]
    assert "version" not in read.json()


def test_if_none_match_gives_304_without_a_body(http):
    path = create(http)
    res = http.get(path, headers={"If-None-Match": '"1"'})
    assert res.status_code == 304 and res.content == b"" and res.headers["etag"] == '"1"'
    assert http.get(path, headers={"If-None-Match": '"0"'}).status_code == 200


def test_patch_without_if_match_is_428(http):
    res = patch(http, create(http), '{"customer": "x@example.com"}')
    assert res.status_code == 428 and res.json()["type"].endswith("precondition-required")


def test_patch_with_current_etag_succeeds_and_bumps_it(http):
    res = patch(http, create(http), '{"customer": "x@example.com"}', If_Match='"1"')
    assert res.status_code == 200 and res.headers["etag"] == '"2"'


def test_stale_etag_is_412_and_nothing_changes(http):
    path = create(http)
    patch(http, path, '{"customer": "alice@example.com"}', If_Match='"1"')
    bob = patch(http, path, '{"customer": "bob@example.com"}', If_Match='"1"')
    assert bob.status_code == 412
    assert http.get(path).json()["customer"] == "alice@example.com"


def test_lost_update_is_prevented_under_real_concurrency(http):
    path = create(http)
    with ThreadPoolExecutor(2) as pool:
        codes = sorted(pool.map(
            lambda who: patch(http, path, f'{{"customer": "{who}@example.com"}}', If_Match='"1"').status_code,
            ["a", "b"]))
    assert codes == [200, 412]
    assert http.get(path).headers["etag"] == '"2"'


def test_if_match_star_matches_any_version(http):
    assert patch(http, create(http), '{"customer": "x@example.com"}', If_Match="*").status_code == 200
