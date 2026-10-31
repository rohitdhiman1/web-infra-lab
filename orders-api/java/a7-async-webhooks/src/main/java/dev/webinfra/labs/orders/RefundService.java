package dev.webinfra.labs.orders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Refunds take a while, so the API accepts them (202) and finishes in the background.
 * When one completes, every webhook subscriber gets a signed "refund.succeeded" event,
 * retried with exponential backoff until it answers 2xx: at-least-once delivery.
 */
@Service
public class RefundService {
  public record Refund(String id, String orderId, long amount, String status, Instant createdAt, Instant completedAt) {}

  public record Subscription(String id, String url, String secret) {}

  private final Map<String, Refund> refunds = new ConcurrentHashMap<>();
  private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
  private final AtomicLong ids = new AtomicLong(1);
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json;
  private final long delayMs;
  private final long retryBaseMs;
  private final int maxAttempts;

  public RefundService(ObjectMapper json, @Value("${refund.delay-ms}") long delayMs,
                       @Value("${webhook.retry-base-ms}") long retryBaseMs, @Value("${webhook.max-attempts}") int maxAttempts) {
    this.json = json;
    this.delayMs = delayMs;
    this.retryBaseMs = retryBaseMs;
    this.maxAttempts = maxAttempts;
  }

  public Refund start(String orderId, long amount) {
    Refund r = new Refund("ref_" + ids.getAndIncrement(), orderId, amount, "pending", Instant.now(), null);
    refunds.put(r.id(), r);
    scheduler.schedule(() -> complete(r.id()), delayMs, TimeUnit.MILLISECONDS);
    return r;
  }

  public Optional<Refund> find(String id) {
    return Optional.ofNullable(refunds.get(id));
  }

  public Subscription subscribe(String url) {
    var s = new Subscription("wh_" + ids.getAndIncrement(), url, WebhookSigner.newSecret());
    subscriptions.add(s);
    return s;
  }

  private void complete(String id) {
    Refund done = refunds.computeIfPresent(id, (k, r) ->
        new Refund(r.id(), r.orderId(), r.amount(), "succeeded", r.createdAt(), Instant.now()));
    String event = json.writeValueAsString(Map.of("type", "refund.succeeded", "data", done));
    for (Subscription s : subscriptions) {
      String messageId = "msg_" + id + "_" + s.id();   // same id on every retry of this event
      deliver(s, messageId, event, 1);
    }
  }

  private void deliver(Subscription s, String messageId, String body, int attempt) {
    long ts = Instant.now().getEpochSecond();
    HttpRequest req = HttpRequest.newBuilder(URI.create(s.url()))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json")
        .header("webhook-id", messageId)
        .header("webhook-timestamp", String.valueOf(ts))
        .header("webhook-signature", WebhookSigner.sign(s.secret(), messageId, ts, body))
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
      boolean ok = err == null && res.statusCode() / 100 == 2;
      if (!ok && attempt < maxAttempts) {
        long wait = retryBaseMs << (attempt - 1);   // 1x, 2x, 4x, 8x the base
        scheduler.schedule(() -> deliver(s, messageId, body, attempt + 1), wait, TimeUnit.MILLISECONDS);
      }
      // After maxAttempts a real system parks the event in a dead-letter list and alerts someone.
    });
  }
}
