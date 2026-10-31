"""The same cases as the Java lab's PaginationTest."""

import re

import httpx2
import pytest

from app import db

ORDER = {"customer": "ada@example.com", "currency": "EUR", "items": [{"sku": "BOOK-42", "quantity": 1, "unitPrice": 1500}]}


@pytest.fixture
def http(base_url):
    with httpx2.Client(base_url=base_url, timeout=5) as c:
        for _ in range(5):
            c.post("/orders", json=ORDER)
        yield c


def ids(res) -> list[str]:
    return [o["id"] for o in res.json()["data"]]


def test_cursor_pages_through_everything_newest_first(http):
    p1 = http.get("/orders", params={"limit": 2})
    assert ids(p1) == ["ord_5", "ord_4"]
    assert re.fullmatch(r'<(.+)>; rel="next"', p1.headers["link"]).group(1) == p1.json()["next"]
    p2 = http.get(p1.json()["next"])
    assert ids(p2) == ["ord_3", "ord_2"]
    p3 = http.get(p2.json()["next"])
    assert ids(p3) == ["ord_1"] and p3.json()["next"] is None and "link" not in p3.headers


def test_cursor_is_stable_when_orders_arrive_between_pages(http):
    p1 = http.get("/orders", params={"limit": 2})
    http.post("/orders", json=ORDER)  # ord_6 arrives at the top
    assert ids(http.get(p1.json()["next"])) == ["ord_3", "ord_2"]


def test_offset_repeats_a_row_when_orders_arrive_between_pages(http):
    p1 = http.get("/orders", params={"limit": 2, "offset": 0})
    assert ids(p1) == ["ord_5", "ord_4"]
    http.post("/orders", json=ORDER)  # everything shifts down by one
    assert ids(http.get(p1.json()["next"])) == ["ord_4", "ord_3"]  # ord_4 again


def test_filters_are_kept_in_the_next_link(http):
    assert "status=pending" in http.get("/orders", params={"limit": 1, "status": "pending"}).json()["next"]
    assert http.get("/orders", params={"status": "cancelled"}).json()["data"] == []


@pytest.mark.parametrize("query", ["limit=0", "limit=101", "limit=ten", "cursor=garbage", "cursor=aWQ6NQ&offset=2",
                                   "status=lost"])
def test_bad_parameters_are_400(http, query):
    res = http.get("/orders?" + query)
    assert res.status_code == 400 and res.json()["type"].endswith("invalid-parameter")
