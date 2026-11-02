"""Authentication: who is calling? Users send a JWT, services send an API key.

The /token endpoint stands in for an identity provider (Keycloak, Auth0, Entra ID, Okta) so the lab
runs offline. A real one authenticates the user first and runs an OAuth flow; this one trusts the
user name. Verifying the token, the API's side, is exactly what you'd write against a real provider.
"""

import hashlib
import json
import os
import time
import uuid
from dataclasses import dataclass

import jwt
from cryptography.hazmat.primitives.asymmetric import ec
from jwt.algorithms import ECAlgorithm

from .http import ApiError

ISSUER = os.environ.get("AUTH_ISSUER", "https://id.orders.test")
AUDIENCE = os.environ.get("AUTH_AUDIENCE", "orders-api")
TOKEN_TTL = int(os.environ.get("AUTH_TOKEN_TTL_SECONDS", "300"))

# The issuer's P-256 key pair, for ES256. Generated at startup, so a restart invalidates every token.
# A real provider keeps the private key in a KMS or HSM, rotates it, and publishes the public half
# as a JWKS that APIs fetch (PyJWKClient does the fetching and caching).
PRIVATE_KEY = ec.generate_private_key(ec.SECP256R1())
PUBLIC_KEY = PRIVATE_KEY.public_key()
KEY_ID = str(uuid.uuid4())  # lets verifiers pick the right key during a rotation

USERS = {
    "alice": ("customer", "orders:read orders:write"),
    "bob": ("customer", "orders:read orders:write"),
    "sam": ("support", "orders:read orders:read:any"),
    "ana": ("admin", "orders:read orders:read:any orders:write refunds:write"),
}


def sha256(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


# Service callers. Only a hash of each key is stored, so a leaked database leaks no working keys.
# (A fast hash is fine for long random keys; passwords need a slow one such as Argon2 or bcrypt.)
# The warehouse key is "wh_test_4f9c2a7d1e8b6035": a throwaway lab value.
API_KEYS = {
    sha256("wh_test_4f9c2a7d1e8b6035"): ("warehouse", frozenset({"orders:read", "orders:read:any"})),
}


@dataclass(frozen=True)
class Caller:
    """Who is calling, however they authenticated. Handlers see this, never the raw credential."""

    id: str
    role: str | None
    scopes: frozenset[str]

    def has(self, scope: str) -> bool:
        return scope in self.scopes

    def require(self, scope: str) -> None:
        """Function-level authorisation: 403, and the header names the scope an OAuth client would need."""
        if not self.has(scope):
            raise ApiError(403, "insufficient-scope", f"this needs the {scope} scope",
                           headers={"WWW-Authenticate": f'Bearer error="insufficient_scope", scope="{scope}"'})


def issue_token(user: str) -> str:
    role, scope = USERS[user]
    now = int(time.time())
    claims = {
        "iss": ISSUER,          # who made it
        "aud": AUDIENCE,        # which API it's for
        "sub": user,            # who it's about
        "iat": now,
        "exp": now + TOKEN_TTL,  # short-lived: there's no way to revoke a JWT early
        "jti": str(uuid.uuid4()),
        "scope": scope,
        "role": role,
    }
    return jwt.encode(claims, PRIVATE_KEY, algorithm="ES256", headers={"kid": KEY_ID})


def jwks() -> dict:
    """The public key only, in the format verifiers fetch. Safe to publish: it can only verify."""
    key = json.loads(ECAlgorithm.to_jwk(PUBLIC_KEY))
    return {"keys": [key | {"kid": KEY_ID, "use": "sig", "alg": "ES256"}]}


class Unauthenticated(Exception):
    def __init__(self, type_: str, detail: str, challenge: str):
        self.type, self.detail, self.challenge = type_, detail, challenge


def authenticate(authorization: str | None, api_key: str | None) -> Caller:
    """Raises Unauthenticated (401) unless there's exactly one valid credential."""
    if api_key is not None:
        client = API_KEYS.get(sha256(api_key))
        if client is None:
            raise Unauthenticated("invalid-token", "unknown API key", 'ApiKey realm="orders-api"')
        return Caller(client[0], "service", client[1])
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise Unauthenticated("unauthenticated", "send Authorization: Bearer <token>, or X-API-Key",
                              'Bearer realm="orders-api"')  # no credential at all: no error code, just "how"
    try:
        # Only ES256 is accepted, so "alg": "none" or an HS256 token signed with the public key fails.
        claims = jwt.decode(token, PUBLIC_KEY, algorithms=["ES256"], audience=AUDIENCE, issuer=ISSUER,
                            leeway=30,  # allow 30 s of clock skew between servers
                            options={"require": ["exp", "iss", "aud", "sub"]})
    except jwt.InvalidTokenError:
        raise Unauthenticated("invalid-token", "the access token is invalid or expired",
                              'Bearer realm="orders-api", error="invalid_token"') from None
    return Caller(claims["sub"], claims.get("role"), frozenset(claims.get("scope", "").split()))
