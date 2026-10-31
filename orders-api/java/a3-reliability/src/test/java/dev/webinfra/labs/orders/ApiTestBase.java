package dev.webinfra.labs.orders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Needs Postgres from compose.yaml: docker compose up -d */
abstract class ApiTestBase {
  @LocalServerPort int port;
  @Autowired JdbcClient db;
  @Autowired FakeGateway gateway;
  final HttpClient http = HttpClient.newHttpClient();

  static final String NEW_ORDER = """
      {"customer": "ada@example.com", "currency": "EUR",
       "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}""";

  @BeforeEach
  void cleanDatabase() {
    db.sql("TRUNCATE orders, payments, idempotency_keys RESTART IDENTITY CASCADE").update();
    gateway.reset();
  }

  URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  HttpRequest.Builder post(String path, String body) {
    return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(body));
  }

  HttpResponse<String> send(HttpRequest r) throws Exception {
    return http.send(r, BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(uri(path)).build());
  }

  String createOrder() throws Exception {
    return send(post("/orders", NEW_ORDER).build()).headers().firstValue("Location").orElseThrow();
  }

  HttpRequest payment(String orderPath, String key, long amount, Duration timeout) {
    var b = post(orderPath + "/payments", "{\"amount\": " + amount + "}").timeout(timeout);
    if (key != null) b.header("Idempotency-Key", key);
    return b.build();
  }

  static String newKey() {
    return UUID.randomUUID().toString();
  }
}
