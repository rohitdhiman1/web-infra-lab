package dev.webinfra.labs.orders;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Version 2: "customer" became an object, {"email", "name"}. Changing a field's type breaks every
 * client that reads it, so it needed a new version. Both versions serve the same stored orders.
 */
@RestController
@RequestMapping(path = "/v2/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class V2OrderController {
  public record Customer(String email, String name) {}

  public record OrderV2(String id, Customer customer, String currency, List<StoredOrder.Item> items,
                        String note, long total, String status, Instant createdAt) {}

  public record NewOrderV2(Customer customer, String currency, List<StoredOrder.Item> items, String note) {}

  private final OrderStore store;

  public V2OrderController(OrderStore store) {
    this.store = store;
  }

  static OrderV2 view(StoredOrder o) {
    return new OrderV2(o.id(), new Customer(o.customerEmail(), o.customerName()), o.currency(), o.items(),
        o.note(), o.total(), o.status(), o.createdAt());
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OrderV2> create(@RequestBody NewOrderV2 in) {
    String email = in.customer() == null ? null : in.customer().email();
    Validation.check(email, "#/customer/email", in.currency(), in.items());
    StoredOrder o = store.create(email, in.customer().name(), in.currency(), in.items(), in.note());
    return ResponseEntity.created(URI.create("/v2/orders/" + o.id())).body(view(o));
  }

  @GetMapping("/{id}")
  public OrderV2 get(@PathVariable String id) {
    return view(store.find(id).orElseThrow(() -> Validation.notFound(id)));
  }
}
