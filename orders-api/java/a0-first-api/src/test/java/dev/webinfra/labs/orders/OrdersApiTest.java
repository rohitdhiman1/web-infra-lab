package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Tests talk real HTTP to a real server on a random port, so they check exactly
 * what curl would see: status line, headers and body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrdersApiTest {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();

  static final String NEW_ORDER = """
      {"customer": "ada@example.com", "currency": "EUR",
       "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}
      """;

  HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Accept", "application/json");
    if (body != null) b.header("Content-Type", "application/json");
    b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    return http.send(b.build(), BodyHandlers.ofString());
  }

  @Test
  void createReturns201WithLocationAndComputedTotal() throws Exception {
    var res = send("POST", "/orders", NEW_ORDER);
    assertThat(res.statusCode()).isEqualTo(201);
    String location = res.headers().firstValue("Location").orElseThrow();
    assertThat(location).matches(".*/orders/ord_\\d+");
    assertThat(res.body()).contains("\"total\":3000").contains("\"status\":\"pending\"");

    var read = send("GET", URI.create(location).getPath(), null);
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(read.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
  }

  @Test
  void listIsWrappedInAnObject() throws Exception {
    send("POST", "/orders", NEW_ORDER);
    var res = send("GET", "/orders", null);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).startsWith("{\"data\":[");
  }

  @Test
  void unknownOrderIs404WithJsonBody() throws Exception {
    var res = send("GET", "/orders/ord_999999", null);
    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(res.body()).contains("\"error\"");
  }

  @Test
  void clientCannotSetServerOwnedFields() throws Exception {
    var res = send("POST", "/orders", NEW_ORDER.replace("\"customer\"", "\"total\": 1, \"id\": \"mine\", \"customer\""));
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.body()).contains("\"total\":3000").doesNotContain("mine");
  }
}
