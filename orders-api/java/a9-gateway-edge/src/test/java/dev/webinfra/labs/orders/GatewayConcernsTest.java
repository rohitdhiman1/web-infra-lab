package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayConcernsTest {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> send(String method, String path, String... headers) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody());
    if (headers.length > 0) b.headers(headers);
    return http.send(b.build(), BodyHandlers.ofString());
  }

  static String key() {
    return "key-" + UUID.randomUUID();   // a fresh bucket per test
  }

  // ---- request ids ----

  @Test void requestIdIsGeneratedWhenAbsent() throws Exception {
    String id = send("GET", "/orders", "X-API-Key", key()).headers().firstValue("X-Request-Id").orElseThrow();
    assertThat(UUID.fromString(id)).isNotNull();
  }

  @Test void validRequestIdIsEchoedAndInvalidOneReplaced() throws Exception {
    assertThat(send("GET", "/orders", "X-API-Key", key(), "X-Request-Id", "abc-123").headers().firstValue("X-Request-Id"))
        .hasValue("abc-123");
    String replaced = send("GET", "/orders", "X-API-Key", key(), "X-Request-Id", "bad id; DROP TABLE")
        .headers().firstValue("X-Request-Id").orElseThrow();
    assertThat(replaced).isNotEqualTo("bad id; DROP TABLE");
  }

  @Test void problemBodiesCarryTheRequestId() throws Exception {
    var res = send("GET", "/orders/ord_9", "X-API-Key", key(), "X-Request-Id", "trace-me-42");
    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(res.body()).contains("\"requestId\":\"trace-me-42\"");
  }

  // ---- rate limiting ----

  @Test void burstOfFiveThenA429WithRetryAfter() throws Exception {
    String k = key();
    for (int i = 4; i >= 0; i--) {
      var ok = send("GET", "/orders", "X-API-Key", k);
      assertThat(ok.statusCode()).isEqualTo(200);
      assertThat(ok.headers().firstValue("X-RateLimit-Remaining")).hasValue(String.valueOf(i));
    }
    var limited = send("GET", "/orders", "X-API-Key", k);
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(limited.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
    assertThat(limited.body()).contains("rate-limited").contains("requestId");
  }

  @Test void limitsArePerClient() throws Exception {
    String a = key();
    for (int i = 0; i < 6; i++) send("GET", "/orders", "X-API-Key", a);
    assertThat(send("GET", "/orders", "X-API-Key", key()).statusCode()).isEqualTo(200);
  }

  @Test void tokensRefillOverTime() throws Exception {
    String k = key();
    for (int i = 0; i < 6; i++) send("GET", "/orders", "X-API-Key", k);
    Thread.sleep(1100);
    assertThat(send("GET", "/orders", "X-API-Key", k).statusCode()).isEqualTo(200);
  }

  // ---- CORS ----

  @Test void preflightFromTheShopIsAllowed() throws Exception {
    var res = send("OPTIONS", "/orders", "Origin", "https://shop.example.com",
        "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type, x-api-key");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://shop.example.com");
    assertThat(res.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("POST");
    assertThat(res.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase()).contains("x-api-key");
    assertThat(res.headers().firstValue("Access-Control-Max-Age")).hasValue("600");
  }

  @Test void preflightFromAnotherSiteGetsNoAllowOrigin() throws Exception {
    var res = send("OPTIONS", "/orders", "Origin", "https://evil.example", "Access-Control-Request-Method", "POST");
    assertThat(res.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    assertThat(res.statusCode()).isEqualTo(403);
  }

  @Test void corsResponsesExposeTheRequestId() throws Exception {
    var res = send("GET", "/orders", "X-API-Key", key(), "Origin", "https://shop.example.com");
    assertThat(res.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://shop.example.com");
    assertThat(res.headers().firstValue("Access-Control-Expose-Headers").orElseThrow()).contains("X-Request-Id");
  }
}
