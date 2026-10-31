"""The same cases as the Java lab's VersioningTest."""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
ITEMS = {"currency": "EUR", "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}


def test_v1_has_a_string_customer():
    res = client.post("/v1/orders", json={"customer": "ada@example.com"} | ITEMS)
    assert res.status_code == 201 and res.json()["customer"] == "ada@example.com"


def test_v2_has_an_object_customer():
    res = client.post("/v2/orders", json={"customer": {"email": "ada@example.com", "name": "Ada"}} | ITEMS)
    assert res.status_code == 201 and res.json()["customer"] == {"email": "ada@example.com", "name": "Ada"}


def test_both_versions_serve_the_same_order():
    loc = client.post("/v2/orders", json={"customer": {"email": "ada@example.com", "name": "Ada"}} | ITEMS).headers["location"]
    order_id = loc.removeprefix("/v2/orders/")
    assert client.get(f"/v1/orders/{order_id}").json()["customer"] == "ada@example.com"
    assert client.get(f"/v2/orders/{order_id}").json()["customer"]["name"] == "Ada"


def test_v1_announces_its_deprecation():
    res = client.get("/v1/orders/ord_999999")
    assert res.headers["deprecation"] == "@1793491200"
    assert res.headers["sunset"] == "Fri, 30 Apr 2027 00:00:00 GMT"
    links = res.headers.get_list("link")
    assert any('rel="deprecation"' in link for link in links) and any('rel="successor-version"' in link for link in links)


def test_v2_is_not_deprecated():
    assert "deprecation" not in client.get("/v2/orders/ord_999999").headers


def test_additive_field_is_optional_and_unknown_fields_are_ignored():
    assert client.post("/v1/orders", json={"customer": "ada@example.com"} | ITEMS).status_code == 201
    assert client.post("/v1/orders", json={"customer": "ada@example.com", "giftWrap": True} | ITEMS).status_code == 201
    res = client.post("/v1/orders", json={"customer": "ada@example.com", "note": "leave at door"} | ITEMS)
    assert res.json()["note"] == "leave at door"


def test_v2_validates_the_nested_email():
    res = client.post("/v2/orders", json={"customer": {"name": "Ada"}} | ITEMS)
    assert res.status_code == 422 and any(e["pointer"] == "#/customer/email" for e in res.json()["errors"])
