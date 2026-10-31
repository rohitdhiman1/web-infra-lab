package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Needs Postgres from compose.yaml: docker compose up -d */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConditionalRequestsTest {
  @LocalServerPort int port;
  @Autowired JdbcClient db;
  final HttpClient http = HttpClient.newHttpClient();
  static final String NEW_ORDER = """
      {"customer": "ada@example.com", "currency": "EUR",
       "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}""";

  @BeforeEach void clean() {
    db.sql("TRUNCATE orders RESTART IDENTITY").update();
  }

  HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (headers.length > 0) b.headers(headers);
    b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    return http.send(b.build(), BodyHandlers.ofString());
  }

  HttpResponse<String> patch(String path, String body, String... extra) throws Exception {
    String[] h = new String[extra.length + 2];
    h[0] = "Content-Type";
    h[1] = "application/merge-patch+json";
    System.arraycopy(extra, 0, h, 2, extra.length);
    return send("PATCH", path, body, h);
  }

  String create() throws Exception {
    return send("POST", "/orders", NEW_ORDER, "Content-Type", "application/json").headers().firstValue("Location").orElseThrow();
  }

  String etag(HttpResponse<?> r) {
    return r.headers().firstValue("ETag").orElseThrow();
  }

  @Test void responsesCarryAnETagAndCacheControl() throws Exception {
    var created = send("POST", "/orders", NEW_ORDER, "Content-Type", "application/json");
    assertThat(etag(created)).isEqualTo("\"1\"");
    var read = send("GET", created.headers().firstValue("Location").orElseThrow(), null);
    assertThat(etag(read)).isEqualTo("\"1\"");
    assertThat(read.headers().firstValue("Cache-Control").orElseThrow()).contains("no-cache").contains("private");
    assertThat(read.body()).doesNotContain("version");
  }

  @Test void ifNoneMatchGives304WithoutABody() throws Exception {
    String path = create();
    var res = send("GET", path, null, "If-None-Match", "\"1\"");
    assertThat(res.statusCode()).isEqualTo(304);
    assertThat(res.body()).isEmpty();
    assertThat(etag(res)).isEqualTo("\"1\"");
    assertThat(send("GET", path, null, "If-None-Match", "\"0\"").statusCode()).isEqualTo(200);
  }

  @Test void patchWithoutIfMatchIs428() throws Exception {
    var res = patch(create(), "{\"customer\": \"x@example.com\"}");
    assertThat(res.statusCode()).isEqualTo(428);
    assertThat(res.body()).contains("precondition-required");
  }

  @Test void patchWithCurrentETagSucceedsAndBumpsIt() throws Exception {
    String path = create();
    var res = patch(path, "{\"customer\": \"x@example.com\"}", "If-Match", "\"1\"");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(etag(res)).isEqualTo("\"2\"");
  }

  @Test void staleETagIs412AndNothingChanges() throws Exception {
    String path = create();
    patch(path, "{\"customer\": \"alice@example.com\"}", "If-Match", "\"1\"");     // Alice saves first
    var bob = patch(path, "{\"customer\": \"bob@example.com\"}", "If-Match", "\"1\""); // Bob read version 1 too
    assertThat(bob.statusCode()).isEqualTo(412);
    assertThat(send("GET", path, null).body()).contains("alice@example.com");
  }

  @Test void lostUpdateIsPreventedUnderRealConcurrency() throws Exception {
    String path = create();
    var a = CompletableFuture.supplyAsync(() -> code(path, "{\"customer\": \"a@example.com\"}"));
    var b = CompletableFuture.supplyAsync(() -> code(path, "{\"customer\": \"b@example.com\"}"));
    assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 412);
    assertThat(etag(send("GET", path, null))).isEqualTo("\"2\"");
  }

  @Test void ifMatchStarMatchesAnyVersion() throws Exception {
    assertThat(patch(create(), "{\"customer\": \"x@example.com\"}", "If-Match", "*").statusCode()).isEqualTo(200);
  }

  int code(String path, String body) {
    try {
      return patch(path, body, "If-Match", "\"1\"").statusCode();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
