package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A0: the smallest useful API. Three operations on one resource collection:
 *   GET  /orders        list orders
 *   POST /orders        create an order -> 201 Created + Location
 *   GET  /orders/{id}   read one order  -> 200, or 404 if it doesn't exist
 */
@RestController
@RequestMapping("/orders")
public class OrderController {
  private final OrderStore store;

  public OrderController(OrderStore store) {
    this.store = store;
  }

  @GetMapping
  public Map<String, List<Order>> list() {
    // Wrap the array in an object: you can add paging fields later without breaking clients.
    return Map.of("data", store.all());
  }

  @PostMapping
  public ResponseEntity<Order> create(@RequestBody NewOrder body) {
    Order order = store.create(body);
    return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> get(@PathVariable String id) {
    return store.find(id)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "order " + id + " not found")));
  }
}
