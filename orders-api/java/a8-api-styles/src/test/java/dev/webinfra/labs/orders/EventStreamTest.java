package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "events.heartbeat-ms=200")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)   // fresh event log per test
class EventStreamTest {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();

  void createOrder(String customer) throws Exception {
    http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString("{\"customer\": \"" + customer + "\", \"currency\": \"EUR\","
            + " \"items\": [{\"sku\": \"BOOK-42\", \"quantity\": 1, \"unitPrice\": 1500}]}")).build(), BodyHandlers.ofString());
  }

  /** Opens the stream and returns a reader over its lines. */
  BufferedReader open(String lastEventId, List<String> contentType) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/events")).header("Accept", "text/event-stream");
    if (lastEventId != null) b.header("Last-Event-ID", lastEventId);
    HttpResponse<java.io.InputStream> res = http.send(b.build(), BodyHandlers.ofInputStream());
    contentType.add(res.headers().firstValue("Content-Type").orElse(""));
    return new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8));
  }

  /** Reads lines until n complete events (blank-line terminated) with an id have been seen. */
  static List<String> readEvents(BufferedReader in, int n) throws Exception {
    List<String> events = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    String line;
    while (events.size() < n && (line = in.readLine()) != null) {
      if (line.isEmpty()) {
        if (cur.toString().contains("id:")) events.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(line).append('\n');
      }
    }
    return events;
  }

  @Test void streamDeliversEventsAsTheyHappen() throws Exception {
    List<String> ct = new ArrayList<>();
    BufferedReader in = open(null, ct);
    assertThat(ct.getFirst()).startsWith("text/event-stream");
    createOrder("ada@example.com");
    createOrder("bob@example.com");
    List<String> events = readEvents(in, 2);
    assertThat(events.get(0)).contains("id:1").contains("event:order.created").contains("ada@example.com");
    assertThat(events.get(1)).contains("id:2").contains("bob@example.com");
  }

  @Test void reconnectWithLastEventIdResumes() throws Exception {
    createOrder("ada@example.com");
    createOrder("bob@example.com");
    createOrder("cy@example.com");
    BufferedReader in = open("1", new ArrayList<>());   // "I already have event 1"
    List<String> events = readEvents(in, 2);
    assertThat(events.get(0)).contains("id:2").contains("bob@example.com");
    assertThat(events.get(1)).contains("id:3").contains("cy@example.com");
  }

  @Test void idleStreamsGetHeartbeats() throws Exception {
    BufferedReader in = open(null, new ArrayList<>());
    String line;
    boolean sawHeartbeat = false;
    long until = System.currentTimeMillis() + 3000;
    while (!sawHeartbeat && System.currentTimeMillis() < until && (line = in.readLine()) != null) {
      sawHeartbeat = line.startsWith(":");
    }
    assertThat(sawHeartbeat).isTrue();
  }
}
