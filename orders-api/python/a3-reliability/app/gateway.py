"""Stands in for a card processor.

It's slow on purpose (GATEWAY_DELAY_MS) so a client can time out while the charge still goes
through: exactly the case idempotency keys exist for. It counts charges, so tests can prove a
retry didn't charge the card twice. Like real processors, it takes an idempotency key of its own:
we pass ours down, so even a crash after charging can't lead to a second charge.
"""

import itertools
import os
import threading
import time


class FakeGateway:
    def __init__(self, delay_ms: int = 0) -> None:
        self.delay_ms = delay_ms
        self._lock = threading.Lock()
        self.reset()

    def reset(self) -> None:
        self._counter = itertools.count(1)
        self._by_key: dict[str, str] = {}
        self.charges = 0

    def charge(self, idempotency_key: str, order_id: str, amount: int) -> str:
        time.sleep(self.delay_ms / 1000)
        with self._lock:
            if idempotency_key not in self._by_key:
                self.charges += 1
                self._by_key[idempotency_key] = f"ch_{next(self._counter)}"
            return self._by_key[idempotency_key]


gateway = FakeGateway(int(os.environ.get("GATEWAY_DELAY_MS", "0")))
