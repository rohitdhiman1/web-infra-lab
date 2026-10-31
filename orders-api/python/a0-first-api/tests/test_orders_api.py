"""Tests use FastAPI's TestClient, which speaks HTTP to the app in-process,
so they check what curl would see: status, headers and body."""

import re

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

NEW_ORDER = {
    "customer": "ada@example.com",
    "currency": "EUR",
    "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}],
}


def test_create_returns_201_with_location_and_computed_total():
    res = client.post("/orders", json=NEW_ORDER)
    assert res.status_code == 201
    location = res.headers["location"]
    assert re.fullmatch(r"/orders/ord_\d+", location)
    body = res.json()
    assert body["total"] == 3000
    assert body["status"] == "pending"
    assert body["items"][0]["unitPrice"] == 1500  # camelCase on the wire

    read = client.get(location)
    assert read.status_code == 200
    assert read.headers["content-type"].startswith("application/json")


def test_list_is_wrapped_in_an_object():
    client.post("/orders", json=NEW_ORDER)
    res = client.get("/orders")
    assert res.status_code == 200
    assert isinstance(res.json()["data"], list)


def test_unknown_order_is_404_with_json_body():
    res = client.get("/orders/ord_999999")
    assert res.status_code == 404
    assert "error" in res.json()


def test_client_cannot_set_server_owned_fields():
    res = client.post("/orders", json=NEW_ORDER | {"total": 1, "id": "mine"})
    assert res.status_code == 201
    assert res.json()["total"] == 3000 and res.json()["id"] != "mine"
