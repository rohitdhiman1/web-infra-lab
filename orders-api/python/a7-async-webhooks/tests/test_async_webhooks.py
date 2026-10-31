"""The same cases as the Java lab's AsyncAndWebhooksTest."""

import json
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest
from fastapi.testclient import TestClient

from app import main, webhooks

client = TestClient(main.app)


@pytest.fixture(autouse=True)
def fast_timings():
    main.CONFIG.update(refund_delay_ms=200, retry_base_ms=50, max_attempts=5)
    main.SUBSCRIPTIONS.clear()


def start_refund():
    return client.post("/refunds", json={"orderId": "ord_1", "amount": 3000})


def test_refund_is_accepted_not_done():
    res = start_refund()
    assert res.status_code == 202 and res.headers["location"].startswith("/refunds/ref_")
    assert res.headers["retry-after"] == "1" and res.json()["status"] == "pending"


def test_polling_sees_it_finish():
    status = start_refund().headers["location"]
    first = client.get(status)
    assert first.json()["status"] == "pending" and "retry-after" in first.headers
    for _ in range(40):
        body = client.get(status).json()
        if body["status"] == "succeeded":
            break
        time.sleep(0.05)
    assert body["status"] == "succeeded" and body["completedAt"]


def test_unknown_refund_is_404():
    assert client.get("/refunds/ref_999999").status_code == 404


def test_webhook_is_signed_and_retried_until_accepted():
    deliveries, calls = [], Counter()

    class Receiver(BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers["content-length"])).decode()
            deliveries.append((self.headers["webhook-id"], self.headers["webhook-timestamp"],
                               self.headers["webhook-signature"], body))
            calls[self.headers["webhook-id"]] += 1
            self.send_response(500 if calls[self.headers["webhook-id"]] <= 2 else 204)  # fail twice, then accept
            self.end_headers()

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Receiver)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        sub = client.post("/webhooks", json={"url": f"http://127.0.0.1:{server.server_port}/hooks"})
        assert sub.status_code == 201
        secret = sub.json()["secret"]
        assert secret.startswith("whsec_")
        refund_id = start_refund().json()["id"]
        mine = lambda: [d for d in deliveries if f'"id":"{refund_id}"' in d[3]]  # noqa: E731
        for _ in range(60):
            if len(mine()) >= 3:
                break
            time.sleep(0.05)
        time.sleep(0.3)  # give a wrong 4th attempt the chance to show up
        assert len(mine()) == 3                      # 500, 500, 204: then it stops
        assert len({d[0] for d in mine()}) == 1      # same id every time: dedupe on it
        msg_id, ts, sig, body = mine()[-1]
        assert webhooks.verify(secret, msg_id, ts, sig, body, int(time.time()))
        assert json.loads(body)["type"] == "refund.succeeded"
    finally:
        server.shutdown()


def test_receivers_reject_tampering_and_replays():
    secret, now = webhooks.new_secret(), int(time.time())
    sig = webhooks.sign(secret, "msg_1", now, '{"amount":3000}')
    assert webhooks.verify(secret, "msg_1", str(now), sig, '{"amount":3000}', now)
    assert not webhooks.verify(secret, "msg_1", str(now), sig, '{"amount":9999}', now)
    assert not webhooks.verify(secret, "msg_1", str(now), sig, '{"amount":3000}', now + 600)
    assert not webhooks.verify(webhooks.new_secret(), "msg_1", str(now), sig, '{"amount":3000}', now)


def test_webhook_url_must_be_https():
    assert client.post("/webhooks", json={"url": "http://example.com/hooks"}).status_code == 422
