"""A client-side retry loop done properly:

- every attempt has a timeout,
- only requests that are safe to repeat are retried (idempotent methods, or POST with an Idempotency-Key),
- only failures that may succeed later are retried (network errors, timeouts, 408/409-in-progress/429/502/503/504),
- the wait honours Retry-After, otherwise uses exponential backoff with full jitter,
- attempts are capped.
"""

import random
import time
from collections.abc import Callable
from dataclasses import dataclass

import httpx2

IDEMPOTENT = {"GET", "HEAD", "OPTIONS", "PUT", "DELETE"}
RETRYABLE = {408, 429, 502, 503, 504}


@dataclass
class Policy:
    max_attempts: int = 5
    base: float = 0.1  # seconds
    cap: float = 5.0


class RetryingClient:
    def __init__(self, client: httpx2.Client, policy: Policy = Policy(), rng: random.Random | None = None,
                 sleep: Callable[[float], None] = time.sleep):
        self.client, self.policy, self.rng, self.sleep = client, policy, rng or random.Random(), sleep
        self.last_attempts = 0

    def send(self, request: httpx2.Request) -> httpx2.Response:
        retryable = safe_to_retry(request)
        attempt = 0
        while True:
            attempt += 1
            self.last_attempts = attempt
            try:
                response = self.client.send(request)
            except httpx2.TransportError:  # includes timeouts and connection failures
                if not retryable or attempt >= self.policy.max_attempts:
                    raise
                self.sleep(self.backoff(attempt, None))
                continue
            if not retryable or attempt >= self.policy.max_attempts or not retryable_status(response):
                return response
            self.sleep(self.backoff(attempt, response.headers.get("retry-after")))

    def backoff(self, attempt: int, retry_after: str | None) -> float:
        """Full jitter: a random wait between 0 and min(cap, base * 2^(attempt-1)).
        Randomness spreads retries from many clients apart, so they don't arrive in synchronized waves."""
        if retry_after and retry_after.isdigit():
            return float(retry_after)
        return self.rng.uniform(0, min(self.policy.cap, self.policy.base * 2 ** (attempt - 1)))


def safe_to_retry(request: httpx2.Request) -> bool:
    return request.method in IDEMPOTENT or "idempotency-key" in request.headers


def retryable_status(response: httpx2.Response) -> bool:
    # 409 is retryable only when the server says "come back later" (key still in progress).
    return response.status_code in RETRYABLE or (response.status_code == 409 and "retry-after" in response.headers)
