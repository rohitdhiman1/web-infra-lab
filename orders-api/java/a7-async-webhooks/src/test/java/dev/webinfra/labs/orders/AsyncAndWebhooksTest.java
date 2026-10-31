package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"refund.delay-ms=200", "webhook.retry-base-ms=50"})
class AsyncAndWebhooksTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), BodyHandlers.ofString());
  }

  @Test void refundIsAcceptedNotDone() throws Exception {
    var res = post("/refunds", "{\"orderId\": \"ord_1\", \"amount\": 3000}");
    assertThat(res.statusCode()).isEqualTo(202);
    assertThat(res.headers().firstValue("Location").orElseThrow()).matches("/refunds/ref_\\d+");
    assertThat(res.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(res.body()).contains("\"status\":\"pending\"");
  }

  @Test void pollingSeesItFinish() throws Exception {
    String status = post("/refunds", "{\"orderId\": \"ord_1\", \"amount\": 3000}").headers().firstValue("Location").orElseThrow();
    var first = get(status);
    assertThat(first.body()).contains("pending");
    assertThat(first.headers().firstValue("Retry-After")).isPresent();
    String body = "";
    for (int i = 0; i < 40 && !body.contains("succeeded"); i++) {
      Thread.sleep(50);
      body = get(status).body();
    }
    assertThat(body).contains("\"status\":\"succeeded\"").contains("completedAt");
  }

  @Test void unknownRefundIs404() throws Exception {
    assertThat(get("/refunds/ref_999999").statusCode()).isEqualTo(404);
  }

  @Test void webhookIsSignedAndRetriedUntilAccepted() throws Exception {
    // A receiver that fails twice, then accepts.
    record Delivery(String id, String timestamp, String signature, String body) {}
    List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
    HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/hooks", ex -> {
      var h = ex.getRequestHeaders();
      deliveries.add(new Delivery(h.getFirst("webhook-id"), h.getFirst("webhook-timestamp"),
          h.getFirst("webhook-signature"), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      int n = calls.computeIfAbsent(h.getFirst("webhook-id"), k -> new AtomicInteger()).incrementAndGet();
      ex.sendResponseHeaders(n <= 2 ? 500 : 204, -1);   // each event fails twice, then is accepted
      ex.close();
    });
    receiver.start();
    try {
      var sub = post("/webhooks", "{\"url\": \"http://127.0.0.1:" + receiver.getAddress().getPort() + "/hooks\"}");
      assertThat(sub.statusCode()).isEqualTo(201);
      String secret = json.readTree(sub.body()).get("secret").asString();
      assertThat(secret).startsWith("whsec_");

      String refund = post("/refunds", "{\"orderId\": \"ord_1\", \"amount\": 3000}").headers()
          .firstValue("Location").orElseThrow().replace("/refunds/", "");
      // Other tests' refunds may also reach this receiver; look only at this refund's event.
      java.util.function.Supplier<List<Delivery>> mine = () ->
          deliveries.stream().filter(d -> d.body().contains("\"id\":\"" + refund + "\"")).toList();
      for (int i = 0; i < 60 && mine.get().size() < 3; i++) Thread.sleep(50);
      Thread.sleep(300);   // give a wrong 4th attempt the chance to show up

      assertThat(mine.get()).hasSize(3);                                         // 500, 500, 204: then it stops
      assertThat(mine.get().stream().map(Delivery::id).distinct()).hasSize(1);   // same id every time: dedupe on it
      Delivery d = mine.get().getLast();
      assertThat(WebhookSigner.verify(secret, d.id(), d.timestamp(), d.signature(), d.body(), Instant.now().getEpochSecond())).isTrue();
      assertThat(json.readValue(d.body(), Map.class)).containsEntry("type", "refund.succeeded");
    } finally {
      receiver.stop(0);
    }
  }

  @Test void receiversRejectTamperingAndReplays() {
    String secret = WebhookSigner.newSecret();
    long now = Instant.now().getEpochSecond();
    String sig = WebhookSigner.sign(secret, "msg_1", now, "{\"amount\":3000}");
    assertThat(WebhookSigner.verify(secret, "msg_1", String.valueOf(now), sig, "{\"amount\":3000}", now)).isTrue();
    assertThat(WebhookSigner.verify(secret, "msg_1", String.valueOf(now), sig, "{\"amount\":9999}", now)).isFalse();
    assertThat(WebhookSigner.verify(secret, "msg_1", String.valueOf(now), sig, "{\"amount\":3000}", now + 600)).isFalse();
    assertThat(WebhookSigner.verify(WebhookSigner.newSecret(), "msg_1", String.valueOf(now), sig, "{\"amount\":3000}", now)).isFalse();
  }

  @Test void webhookUrlMustBeHttps() throws Exception {
    assertThat(post("/webhooks", "{\"url\": \"http://example.com/hooks\"}").statusCode()).isEqualTo(422);
  }
}
