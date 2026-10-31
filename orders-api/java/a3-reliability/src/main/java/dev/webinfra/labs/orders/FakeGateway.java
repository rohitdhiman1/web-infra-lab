package dev.webinfra.labs.orders;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stands in for a card processor. It's slow on purpose (gateway.delay-ms) so a client can time out
 * while the charge still goes through: exactly the case idempotency keys exist for.
 * It counts charges, so tests can prove a retry didn't charge the card twice.
 *
 * Like real processors (Stripe, Adyen), it accepts an idempotency key of its own: we pass ours
 * down, so even if we crash after charging, a retry can't charge the card a second time.
 */
@Component
public class FakeGateway {
  private final long delayMs;
  private final AtomicInteger charges = new AtomicInteger();
  private final Map<String, String> byKey = new ConcurrentHashMap<>();

  public FakeGateway(@Value("${gateway.delay-ms:0}") long delayMs) {
    this.delayMs = delayMs;
  }

  public String charge(String idempotencyKey, String orderId, long amount) {
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return byKey.computeIfAbsent(idempotencyKey, k -> "ch_" + charges.incrementAndGet());
  }

  public int charges() {
    return charges.get();
  }

  public void reset() {
    charges.set(0);
    byKey.clear();
  }
}
