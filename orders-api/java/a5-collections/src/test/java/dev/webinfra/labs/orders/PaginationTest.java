package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Needs Postgres from compose.yaml: docker compose up -d */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaginationTest {
  @LocalServerPort int port;
  @Autowired JdbcClient db;
  @Autowired ObjectMapper json;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach void fiveOrders() throws Exception {
    db.sql("TRUNCATE orders RESTART IDENTITY").update();
    for (int i = 0; i < 5; i++) create();
  }

  void create() throws Exception {
    http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("""
            {"customer": "ada@example.com", "currency": "EUR",
             "items": [{"sku": "BOOK-42", "quantity": 1, "unitPrice": 1500}]}""")).build(), BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), BodyHandlers.ofString());
  }

  List<String> ids(HttpResponse<String> r) {
    List<String> out = new ArrayList<>();
    json.readTree(r.body()).get("data").forEach(o -> out.add(o.get("id").asString()));
    return out;
  }

  String next(HttpResponse<String> r) {
    JsonNode n = json.readTree(r.body()).get("next");
    return n == null || n.isNull() ? null : n.asString();
  }

  @Test void cursorPagesThroughEverythingNewestFirst() throws Exception {
    var p1 = get("/orders?limit=2");
    assertThat(ids(p1)).containsExactly("ord_5", "ord_4");
    Matcher m = Pattern.compile("<(.+)>; rel=\"next\"").matcher(p1.headers().firstValue("Link").orElseThrow());
    assertThat(m.matches()).isTrue();
    assertThat(m.group(1)).isEqualTo(next(p1));
    var p2 = get(next(p1));
    assertThat(ids(p2)).containsExactly("ord_3", "ord_2");
    var p3 = get(next(p2));
    assertThat(ids(p3)).containsExactly("ord_1");
    assertThat(next(p3)).isNull();
    assertThat(p3.headers().firstValue("Link")).isEmpty();
  }

  @Test void cursorIsStableWhenOrdersArriveBetweenPages() throws Exception {
    var p1 = get("/orders?limit=2");
    create();                                         // ord_6 arrives at the top
    assertThat(ids(get(next(p1)))).containsExactly("ord_3", "ord_2");   // no repeats
  }

  @Test void offsetRepeatsARowWhenOrdersArriveBetweenPages() throws Exception {
    var p1 = get("/orders?limit=2&offset=0");
    assertThat(ids(p1)).containsExactly("ord_5", "ord_4");
    create();                                         // everything shifts down by one
    assertThat(ids(get(next(p1)))).containsExactly("ord_4", "ord_3");   // ord_4 again
  }

  @Test void filtersAreKeptInTheNextLink() throws Exception {
    var p1 = get("/orders?limit=1&status=pending");
    assertThat(next(p1)).contains("status=pending");
    assertThat(get("/orders?status=cancelled").body()).contains("\"data\":[]");
  }

  @Test void badParametersAre400() throws Exception {
    for (String q : List.of("limit=0", "limit=101", "limit=ten", "cursor=garbage", "cursor=aWQ6NQ&offset=2", "status=lost")) {
      var r = get("/orders?" + q);
      assertThat(r.statusCode()).as(q).isEqualTo(400);
      assertThat(r.body()).contains("invalid-parameter");
    }
  }
}
