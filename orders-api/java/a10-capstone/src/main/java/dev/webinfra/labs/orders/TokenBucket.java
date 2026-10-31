package dev.webinfra.labs.orders;

/**
 * A token bucket: holds up to `capacity` tokens and refills at `ratePerSecond`.
 * Each request takes one token. A full bucket allows a burst of `capacity` requests;
 * after that, requests are allowed at the refill rate.
 */
final class TokenBucket {
  record Decision(boolean allowed, int remaining, long retryAfterSeconds) {}

  private final int capacity;
  private final double ratePerSecond;
  private double tokens;
  private long lastNanos;

  TokenBucket(int capacity, double ratePerSecond, long nowNanos) {
    this.capacity = capacity;
    this.ratePerSecond = ratePerSecond;
    this.tokens = capacity;
    this.lastNanos = nowNanos;
  }

  synchronized Decision take(long nowNanos) {
    tokens = Math.min(capacity, tokens + (nowNanos - lastNanos) / 1e9 * ratePerSecond);   // refill
    lastNanos = nowNanos;
    if (tokens >= 1) {
      tokens -= 1;
      return new Decision(true, (int) Math.floor(tokens), 0);
    }
    long wait = (long) Math.ceil((1 - tokens) / ratePerSecond);   // seconds until one whole token
    return new Decision(false, 0, Math.max(1, wait));
  }
}
