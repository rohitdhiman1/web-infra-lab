package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.webinfra.labs.orders.client.RetryingClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** The gateway takes 700 ms, longer than the client is willing to wait. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "gateway.delay-ms=700")
class SlowGatewayTest extends ApiTestBase {

  @Test void timedOutPaymentStillHappensAndRetryGetsTheResult() throws Exception {
    String order = createOrder();
    String key = newKey();
    // 1. The client gives up after 200 ms, but the server keeps going and charges the card.
    assertThatThrownBy(() -> send(payment(order, key, 3000, Duration.ofMillis(200))))
        .isInstanceOf(HttpTimeoutException.class);
    // 2. An immediate retry finds the first request still running.
    var busy = send(payment(order, key, 3000, Duration.ofSeconds(5)));
    assertThat(busy.statusCode()).isEqualTo(409);
    assertThat(busy.headers().firstValue("Retry-After")).hasValue("1");
    // 3. After Retry-After, the retry gets the original 201: one charge, not two.
    Thread.sleep(1000);
    var done = send(payment(order, key, 3000, Duration.ofSeconds(5)));
    assertThat(done.statusCode()).isEqualTo(201);
    assertThat(done.headers().firstValue("Idempotent-Replayed")).hasValue("true");
    assertThat(gateway.charges()).isEqualTo(1);
  }

  @Test void retryingClientRecoversOnItsOwn() throws Exception {
    String order = createOrder();
    var client = new RetryingClient(http, new RetryingClient.Policy(6, Duration.ofMillis(100), Duration.ofSeconds(2)),
        new Random(42), d -> Thread.sleep(d.toMillis()));
    HttpResponse<String> res = client.send(payment(order, newKey(), 3000, Duration.ofMillis(300)));
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(client.lastAttempts()).isGreaterThan(1);
    assertThat(gateway.charges()).isEqualTo(1);
  }

  @Test void concurrentRequestsWithTheSameKeyChargeOnce() throws Exception {
    String order = createOrder();
    String key = newKey();
    var a = CompletableFuture.supplyAsync(() -> status(payment(order, key, 3000, Duration.ofSeconds(5))));
    var b = CompletableFuture.supplyAsync(() -> status(payment(order, key, 3000, Duration.ofSeconds(5))));
    assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
    assertThat(gateway.charges()).isEqualTo(1);
  }

  @Test void concurrentPaymentsWithDifferentKeysChargeOnce() throws Exception {
    String order = createOrder();
    var a = CompletableFuture.supplyAsync(() -> status(payment(order, newKey(), 3000, Duration.ofSeconds(5))));
    var b = CompletableFuture.supplyAsync(() -> status(payment(order, newKey(), 3000, Duration.ofSeconds(5))));
    assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
    assertThat(gateway.charges()).isEqualTo(1);
  }

  int status(java.net.http.HttpRequest r) {
    try {
      return send(r).statusCode();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
