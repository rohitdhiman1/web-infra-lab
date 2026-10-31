package dev.webinfra.labs.orders.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryingClientTest {
  final RetryingClient client = new RetryingClient(HttpClient.newHttpClient(),
      new RetryingClient.Policy(5, Duration.ofMillis(100), Duration.ofSeconds(1)), new Random(1), d -> {});

  @Test void backoffGrowsExponentiallyUpToTheCap() {
    for (int attempt = 1; attempt <= 8; attempt++) {
      long ceiling = Math.min(1000, 100L << (attempt - 1));
      for (int i = 0; i < 200; i++) {
        assertThat(client.backoff(attempt, null).toMillis()).isBetween(0L, ceiling);
      }
    }
  }

  @Test void retryAfterWins() {
    assertThat(client.backoff(1, "3")).isEqualTo(Duration.ofSeconds(3));
  }

  @Test void postIsOnlyRetriedWithAnIdempotencyKey() {
    var plain = HttpRequest.newBuilder(URI.create("http://x/orders")).POST(BodyPublishers.noBody()).build();
    var keyed = HttpRequest.newBuilder(URI.create("http://x/orders")).header("Idempotency-Key", "k")
        .POST(BodyPublishers.noBody()).build();
    var get = HttpRequest.newBuilder(URI.create("http://x/orders")).GET().build();
    assertThat(RetryingClient.safeToRetry(plain)).isFalse();
    assertThat(RetryingClient.safeToRetry(keyed)).isTrue();
    assertThat(RetryingClient.safeToRetry(get)).isTrue();
  }
}
