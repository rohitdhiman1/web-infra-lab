"""The same cases as the Java lab's OrdersApiTest, so both labs meet one spec."""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
MP = {"Content-Type": "application/merge-patch+json"}
NEW_ORDER = {"customer": "ada@example.com", "currency": "EUR",
             "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}


def create() -> str:
    return client.post("/orders", json=NEW_ORDER).headers["location"]


def test_post_creates_201_with_location():
    res = client.post("/orders", json=NEW_ORDER)
    assert res.status_code == 201 and res.headers["location"].startswith("/orders/ord_")


def test_post_twice_creates_two_orders():
    assert create() != create()


def test_broken_json_is_400():
    res = client.post("/orders", content='{"customer": ', headers={"Content-Type": "application/json"})
    assert res.status_code == 400


def test_wrong_type_is_422():
    bad = NEW_ORDER | {"items": [{"sku": "BOOK-42", "quantity": "two", "unitPrice": 1500}]}
    assert client.post("/orders", json=bad).status_code == 422


def test_failed_rules_are_422_with_fields():
    res = client.post("/orders", json={"customer": "", "currency": "euro", "items": []})
    assert res.status_code == 422
    assert {"customer", "currency", "items"} <= res.json()["fields"].keys()


def test_wrong_content_type_is_415():
    res = client.post("/orders", content="hello", headers={"Content-Type": "text/plain"})
    assert res.status_code == 415


def test_unacceptable_accept_is_406():
    assert client.get(create(), headers={"Accept": "application/xml"}).status_code == 406


def test_wrong_method_is_405_with_allow():
    res = client.delete("/orders")
    assert res.status_code == 405
    assert {"GET", "POST"} <= {m.strip() for m in res.headers["allow"].split(",")}


def test_head_has_headers_but_no_body():
    res = client.head(create())
    assert res.status_code == 200 and res.content == b""


def test_put_replaces_and_is_idempotent():
    path = create()
    full = {"customer": "bob@example.com", "currency": "EUR", "status": "pending",
            "items": [{"sku": "PEN-1", "quantity": 1, "unitPrice": 250}]}
    first, second = client.put(path, json=full), client.put(path, json=full)
    assert first.status_code == 200 and first.json() == second.json()
    assert first.json()["total"] == 250


def test_put_missing_field_is_422():
    assert client.put(create(), json={"customer": "bob@example.com"}).status_code == 422


def test_put_unknown_is_404():
    assert client.put("/orders/ord_999999", json=NEW_ORDER).status_code == 404


def test_patch_needs_merge_patch_media_type():
    res = client.patch(create(), json={"customer": "x@example.com"})
    assert res.status_code == 415
    assert "application/merge-patch+json" in res.headers["accept-patch"]


def test_patch_changes_only_sent_fields():
    res = client.patch(create(), content='{"customer": "x@example.com"}', headers=MP)
    assert res.status_code == 200
    assert res.json()["customer"] == "x@example.com" and res.json()["items"][0]["sku"] == "BOOK-42"


def test_patch_null_on_required_field_is_422():
    assert client.patch(create(), content='{"customer": null}', headers=MP).status_code == 422


def test_cancel_is_safe_to_retry_but_cancelled_is_final():
    path = create()
    assert client.patch(path, content='{"status": "cancelled"}', headers=MP).status_code == 200
    assert client.patch(path, content='{"status": "cancelled"}', headers=MP).status_code == 200
    assert client.patch(path, content='{"customer": "y@example.com"}', headers=MP).status_code == 409


def test_delete_is_204_then_repeat_is_404():
    path = create()
    first = client.delete(path)
    assert first.status_code == 204 and first.content == b""
    assert client.delete(path).status_code == 404
    assert client.get(path).status_code == 404
