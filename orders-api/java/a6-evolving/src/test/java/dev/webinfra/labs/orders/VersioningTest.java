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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersioningTest {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();
  static final String ITEMS = "\"currency\": \"EUR\", \"items\": [{\"sku\": \"BOOK-42\", \"quantity\": 2, \"unitPrice\": 1500}]";

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), BodyHandlers.ofString());
  }

  @Test void v1HasAStringCustomer() throws Exception {
    var res = post("/v1/orders", "{\"customer\": \"ada@example.com\", " + ITEMS + "}");
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.body()).contains("\"customer\":\"ada@example.com\"");
  }

  @Test void v2HasAnObjectCustomer() throws Exception {
    var res = post("/v2/orders", "{\"customer\": {\"email\": \"ada@example.com\", \"name\": \"Ada\"}, " + ITEMS + "}");
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.body()).contains("\"customer\":{\"email\":\"ada@example.com\",\"name\":\"Ada\"}");
  }

  @Test void bothVersionsServeTheSameOrder() throws Exception {
    String id = post("/v2/orders", "{\"customer\": {\"email\": \"ada@example.com\", \"name\": \"Ada\"}, " + ITEMS + "}")
        .headers().firstValue("Location").orElseThrow().replace("/v2/orders/", "");
    assertThat(get("/v1/orders/" + id).body()).contains("\"customer\":\"ada@example.com\"");
    assertThat(get("/v2/orders/" + id).body()).contains("\"name\":\"Ada\"");
  }

  @Test void v1AnnouncesItsDeprecation() throws Exception {
    var res = get("/v1/orders/ord_999999");
    assertThat(res.headers().firstValue("Deprecation")).hasValue("@1793491200");
    assertThat(res.headers().firstValue("Sunset")).hasValue("Fri, 30 Apr 2027 00:00:00 GMT");
    assertThat(res.headers().allValues("Link")).anyMatch(l -> l.contains("rel=\"deprecation\""))
        .anyMatch(l -> l.contains("rel=\"successor-version\""));
  }

  @Test void v2IsNotDeprecated() throws Exception {
    assertThat(get("/v2/orders/ord_999999").headers().firstValue("Deprecation")).isEmpty();
  }

  @Test void additiveFieldIsOptionalAndUnknownFieldsAreIgnored() throws Exception {
    var old = post("/v1/orders", "{\"customer\": \"ada@example.com\", " + ITEMS + "}");      // a client from before "note"
    assertThat(old.statusCode()).isEqualTo(201);
    var future = post("/v1/orders", "{\"customer\": \"ada@example.com\", \"giftWrap\": true, " + ITEMS + "}");
    assertThat(future.statusCode()).isEqualTo(201);                                           // tolerant reader
    var withNote = post("/v1/orders", "{\"customer\": \"ada@example.com\", \"note\": \"leave at door\", " + ITEMS + "}");
    assertThat(withNote.body()).contains("\"note\":\"leave at door\"");
  }

  @Test void v2ValidatesTheNestedEmail() throws Exception {
    var res = post("/v2/orders", "{\"customer\": {\"name\": \"Ada\"}, " + ITEMS + "}");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("#/customer/email");
  }
}
