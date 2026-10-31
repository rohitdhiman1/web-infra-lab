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
class OrdersApiTest {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();

  static final String NEW_ORDER = """
      {"customer": "ada@example.com", "currency": "EUR",
       "items": [{"sku": "BOOK-42", "quantity": 2, "unitPrice": 1500}]}""";

  HttpResponse<String> send(String method, String path, String contentType, String body, String accept) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept);
    if (contentType != null) b.header("Content-Type", contentType);
    b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    return http.send(b.build(), BodyHandlers.ofString());
  }
  HttpResponse<String> send(String method, String path, String body) throws Exception {
    return send(method, path, body == null ? null : "application/json", body, "application/json");
  }
  String create() throws Exception {
    return URI.create(send("POST", "/orders", NEW_ORDER).headers().firstValue("Location").orElseThrow()).getPath();
  }

  @Test void postCreates201WithLocation() throws Exception {
    var res = send("POST", "/orders", NEW_ORDER);
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.headers().firstValue("Location")).isPresent();
  }

  @Test void postTwiceCreatesTwoOrders() throws Exception {
    assertThat(create()).isNotEqualTo(create());
  }

  @Test void brokenJsonIs400() throws Exception {
    assertThat(send("POST", "/orders", "{\"customer\": ").statusCode()).isEqualTo(400);
  }

  @Test void wrongTypeIs422() throws Exception {
    var res = send("POST", "/orders", NEW_ORDER.replace("\"quantity\": 2", "\"quantity\": \"two\""));
    assertThat(res.statusCode()).isEqualTo(422);
  }

  @Test void failedRulesAre422WithFields() throws Exception {
    var res = send("POST", "/orders", "{\"customer\": \"\", \"currency\": \"euro\", \"items\": []}");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("\"pointer\":\"#/customer\"").contains("\"pointer\":\"#/currency\"")
        .contains("\"pointer\":\"#/items\"");
  }

  @Test void wrongContentTypeIs415() throws Exception {
    assertThat(send("POST", "/orders", "text/plain", NEW_ORDER, "application/json").statusCode()).isEqualTo(415);
  }

  @Test void unacceptableAcceptIs406() throws Exception {
    assertThat(send("GET", create(), null, null, "application/xml").statusCode()).isEqualTo(406);
  }

  @Test void wrongMethodIs405WithAllow() throws Exception {
    var res = send("DELETE", "/orders", null);
    assertThat(res.statusCode()).isEqualTo(405);
    assertThat(res.headers().firstValue("Allow").orElseThrow()).contains("GET").contains("POST");
  }

  @Test void headHasHeadersButNoBody() throws Exception {
    var res = send("HEAD", create(), null);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).isEmpty();
  }

  @Test void putReplacesAndIsIdempotent() throws Exception {
    String path = create();
    String full = "{\"customer\": \"bob@example.com\", \"currency\": \"EUR\", \"status\": \"pending\","
        + " \"items\": [{\"sku\": \"PEN-1\", \"quantity\": 1, \"unitPrice\": 250}]}";
    var first = send("PUT", path, full);
    var second = send("PUT", path, full);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(first.body()).contains("\"total\":250");
  }

  @Test void putMissingFieldIs422() throws Exception {
    assertThat(send("PUT", create(), "{\"customer\": \"bob@example.com\"}").statusCode()).isEqualTo(422);
  }

  @Test void putUnknownIs404() throws Exception {
    assertThat(send("PUT", "/orders/ord_999999", NEW_ORDER).statusCode()).isEqualTo(404);
  }

  @Test void patchNeedsMergePatchMediaType() throws Exception {
    var res = send("PATCH", create(), "{\"customer\": \"x@example.com\"}");
    assertThat(res.statusCode()).isEqualTo(415);
    assertThat(res.headers().firstValue("Accept-Patch").orElseThrow()).contains("application/merge-patch+json");
  }

  @Test void patchChangesOnlySentFields() throws Exception {
    String path = create();
    var res = send("PATCH", path, "application/merge-patch+json", "{\"customer\": \"x@example.com\"}", "application/json");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("x@example.com").contains("BOOK-42");
  }

  @Test void patchNullOnRequiredFieldIs422() throws Exception {
    var res = send("PATCH", create(), "application/merge-patch+json", "{\"customer\": null}", "application/json");
    assertThat(res.statusCode()).isEqualTo(422);
  }

  @Test void cancelIsSafeToRetryButCancelledIsFinal() throws Exception {
    String path = create();
    String cancel = "{\"status\": \"cancelled\"}";
    assertThat(send("PATCH", path, "application/merge-patch+json", cancel, "application/json").statusCode()).isEqualTo(200);
    assertThat(send("PATCH", path, "application/merge-patch+json", cancel, "application/json").statusCode()).isEqualTo(200);
    var change = send("PATCH", path, "application/merge-patch+json", "{\"customer\": \"y@example.com\"}", "application/json");
    assertThat(change.statusCode()).isEqualTo(409);
  }

  @Test void deleteIs204ThenRepeatIs404() throws Exception {
    String path = create();
    var first = send("DELETE", path, null);
    assertThat(first.statusCode()).isEqualTo(204);
    assertThat(first.body()).isEmpty();
    assertThat(send("DELETE", path, null).statusCode()).isEqualTo(404);
    assertThat(send("GET", path, null).statusCode()).isEqualTo(404);
  }

  // ---- A2: every error is RFC 9457 problem details ----

  void assertProblem(HttpResponse<String> res, int status, String path) {
    assertThat(res.statusCode()).isEqualTo(status);
    assertThat(res.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
    // "type" may be absent: RFC 9457 says that means "about:blank" (a plain HTTP error, no extra meaning).
    assertThat(res.body()).contains("\"status\":" + status).contains("\"title\":")
        .contains("\"instance\":\"" + path + "\"");
  }

  @Test void validationErrorIsAProblemWithPointers() throws Exception {
    var res = send("POST", "/orders", NEW_ORDER.replace("\"quantity\": 2", "\"quantity\": 0"));
    assertProblem(res, 422, "/orders");
    assertThat(res.body()).contains("\"type\":\"https://api.example.com/problems/validation-error\"")
        .contains("\"pointer\":\"#/items/0/quantity\"");
  }

  @Test void notFoundIsAProblem() throws Exception {
    assertProblem(send("GET", "/orders/ord_999999", null), 404, "/orders/ord_999999");
  }

  @Test void unknownPathIsAProblemToo() throws Exception {
    assertProblem(send("GET", "/nope", null), 404, "/nope");
  }

  @Test void frameworkErrorsAreProblemsWithTheirHeaders() throws Exception {
    var r405 = send("DELETE", "/orders", null);
    assertProblem(r405, 405, "/orders");
    assertThat(r405.headers().firstValue("Allow")).isPresent();
    String path = create();
    var r415 = send("PATCH", path, "{}");
    assertProblem(r415, 415, path);
    assertThat(r415.headers().firstValue("Accept-Patch")).isPresent();
  }

  @Test void brokenJsonIsAProblem() throws Exception {
    assertProblem(send("POST", "/orders", "{\"customer\": "), 400, "/orders");
  }

  @Test void conflictIsAProblem() throws Exception {
    String path = create();
    send("PATCH", path, "application/merge-patch+json", "{\"status\": \"cancelled\"}", "application/json");
    var res = send("PATCH", path, "application/merge-patch+json", "{\"customer\": \"z@example.com\"}", "application/json");
    assertProblem(res, 409, path);
    assertThat(res.body()).contains("order-cancelled");
  }

  @Test void openApiDescribesTheContract() throws Exception {
    var res = send("GET", "/v3/api-docs", null);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"/orders/{id}\"").contains("\"201\"").contains("\"422\"");
  }
}
