package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A3 keeps just create and read for orders (A1 covers PUT/PATCH/DELETE) and adds payments. */
@RestController
@RequestMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {
  private final OrderRepository orders;

  public OrderController(OrderRepository orders) {
    this.orders = orders;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Order> create(@RequestBody OrderInput body) {
    var problems = body.problems(false);
    problems.remove("#/status");
    if (!problems.isEmpty()) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", problems);
    }
    Order order = orders.create(body);
    return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
  }

  @GetMapping("/{id}")
  public Order get(@PathVariable String id) {
    return orders.find(Ids.order(id)).orElseThrow(() -> Ids.notFound(id));
  }

  @GetMapping("/{id}/payments")
  public Map<String, Object> payments(@PathVariable String id) {
    long orderId = Ids.order(id);
    orders.find(orderId).orElseThrow(() -> Ids.notFound(id));
    return Map.of("data", orders.payments(orderId));
  }
}
