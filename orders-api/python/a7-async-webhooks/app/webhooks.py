"""Signs and verifies webhooks the way the Standard Webhooks spec (standardwebhooks.com) does.

    webhook-id:        msg_...       unique per event; the same on every retry, so receivers can dedupe
    webhook-timestamp: Unix seconds  lets receivers reject old (replayed) messages
    webhook-signature: v1,<base64>   HMAC-SHA256 over "{id}.{timestamp}.{body}" with the shared secret

Secrets look like "whsec_" + base64(random bytes).
"""

import base64
import hashlib
import hmac
import secrets

TOLERANCE_SECONDS = 300


def new_secret() -> str:
    return "whsec_" + base64.b64encode(secrets.token_bytes(24)).decode()


def sign(secret: str, msg_id: str, timestamp: int, body: str) -> str:
    key = base64.b64decode(secret.removeprefix("whsec_"))
    mac = hmac.new(key, f"{msg_id}.{timestamp}.{body}".encode(), hashlib.sha256).digest()
    return "v1," + base64.b64encode(mac).decode()


def verify(secret: str, msg_id: str, timestamp: str, signature_header: str, body: str, now: int) -> bool:
    """What a receiver runs before trusting a webhook."""
    if not timestamp.isdigit() or abs(now - int(timestamp)) > TOLERANCE_SECONDS:
        return False  # too old (or from the future): a replay?
    expected = sign(secret, msg_id, int(timestamp), body)
    # The header may hold several space-separated signatures (during secret rotation).
    # compare_digest is constant-time: it doesn't leak how many characters matched.
    return any(hmac.compare_digest(expected, candidate) for candidate in signature_header.split(" "))
