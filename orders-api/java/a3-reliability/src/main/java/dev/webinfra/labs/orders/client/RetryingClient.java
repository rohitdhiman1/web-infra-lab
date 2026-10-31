package dev.webinfra.labs.orders.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * A client-side retry loop done properly:
 *  - every attempt has a timeout (set on the HttpRequest),
 *  - only requests that are safe to repeat are retried (idempotent methods, or POST with an Idempotency-Key),
 *  - only failures that may succeed later are retried (network errors, timeouts, 408/409-in-progress/429/502/503/504),
 *  - the wait honours Retry-After, otherwise uses exponential backoff with full jitter,
 *  - attempts are capped.
 */
public final class RetryingClient {
  public record Policy(int maxAttempts, Duration base, Duration cap) {}

  /** Lets tests skip real sleeping. */
  public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");
  static final Set<Integer> RETRYABLE = Set.of(408, 429, 502, 503, 504);

  private final HttpClient http;
  private final Policy policy;
  private final RandomGenerator random;
  private final Sleeper sleeper;
  private int lastAttempts;

  public RetryingClient(HttpClient http, Policy policy, RandomGenerator random, Sleeper sleeper) {
    this.http = http;
    this.policy = policy;
    this.random = random;
    this.sleeper = sleeper;
  }

  public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    boolean retryable = safeToRetry(request);
    for (int attempt = 1; ; attempt++) {
      lastAttempts = attempt;
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {   // includes HttpTimeoutException and connection failures
        if (!retryable || attempt >= policy.maxAttempts()) throw e;
        sleeper.sleep(backoff(attempt, null));
        continue;
      }
      if (!retryable || attempt >= policy.maxAttempts() || !retryableStatus(response)) return response;
      sleeper.sleep(backoff(attempt, response.headers().firstValue("Retry-After").orElse(null)));
    }
  }

  public int lastAttempts() {
    return lastAttempts;
  }

  static boolean safeToRetry(HttpRequest r) {
    return IDEMPOTENT.contains(r.method()) || r.headers().firstValue("Idempotency-Key").isPresent();
  }

  static boolean retryableStatus(HttpResponse<?> r) {
    // 409 is retryable only when the server says "come back later" (key still in progress).
    return RETRYABLE.contains(r.statusCode())
        || (r.statusCode() == 409 && r.headers().firstValue("Retry-After").isPresent());
  }

  /**
   * Full jitter: wait a random time between 0 and min(cap, base * 2^(attempt-1)).
   * Randomness spreads retries from many clients apart, so they don't arrive in synchronized waves.
   */
  Duration backoff(int attempt, String retryAfter) {
    if (retryAfter != null && retryAfter.matches("\\d+")) {
      return Duration.ofSeconds(Long.parseLong(retryAfter));
    }
    long ceiling = Math.min(policy.cap().toMillis(), policy.base().toMillis() << Math.min(attempt - 1, 20));
    return Duration.ofMillis(random.nextLong(ceiling + 1));
  }
}
