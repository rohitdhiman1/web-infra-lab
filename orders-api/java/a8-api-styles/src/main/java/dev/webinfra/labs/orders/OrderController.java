package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A8: the same orders, two styles.
 *   POST /orders          request/response (as before)
 *   GET  /orders/events   a Server-Sent Events stream of "order.created" events.
 *                         Reconnect with Last-Event-ID to resume where you left off.
 */
@RestController
public class OrderController {
  public record Item(String sku, Integer quantity, Long unitPrice) {}

  public record NewOrder(String customer, String currency, List<Item> items) {}

  private final OrderEvents events;
  private final AtomicLong ids = new AtomicLong(1);

  public OrderController(OrderEvents events) {
    this.events = events;
  }

  @PostMapping(path = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> create(@RequestBody NewOrder in) {
    if (in.customer() == null || in.items() == null || in.items().isEmpty()) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed",
          Map.of("#/items", "customer and at least one item are required"));
    }
    String id = "ord_" + ids.getAndIncrement();
    long total = in.items().stream().mapToLong(i -> i.quantity() * i.unitPrice()).sum();
    Map<String, Object> order = Map.of("id", id, "customer", in.customer(), "total", total, "status", "pending");
    events.publish("order.created", OrderEvents.created(id, in.customer(), total));
    return ResponseEntity.created(URI.create("/orders/" + id)).body(order);
  }

  @GetMapping(path = "/orders/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    long after = 0;
    if (lastEventId != null && lastEventId.matches("\\d{1,18}")) after = Long.parseLong(lastEventId);
    return events.subscribe(after);
  }
}
