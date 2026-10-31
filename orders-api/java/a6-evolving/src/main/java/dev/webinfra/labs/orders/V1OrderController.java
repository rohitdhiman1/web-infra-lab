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
 * Version 1: "customer" is an email string. Deprecated (see DeprecationHeaders), still fully working.
 * v1 also gained "note" after launch: an additive, optional field, so no new version was needed.
 */
@RestController
@RequestMapping(path = "/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class V1OrderController {
  public record OrderV1(String id, String customer, String currency, List<StoredOrder.Item> items,
                        String note, long total, String status, Instant createdAt) {}

  /** "note" was added after v1 launched. It is optional, so old clients never notice it. */
  public record NewOrderV1(String customer, String currency, List<StoredOrder.Item> items, String note) {}

  private final OrderStore store;

  public V1OrderController(OrderStore store) {
    this.store = store;
  }

  static OrderV1 view(StoredOrder o) {
    return new OrderV1(o.id(), o.customerEmail(), o.currency(), o.items(), o.note(), o.total(), o.status(), o.createdAt());
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OrderV1> create(@RequestBody NewOrderV1 in) {
    Validation.check(in.customer(), "#/customer", in.currency(), in.items());
    StoredOrder o = store.create(in.customer(), null, in.currency(), in.items(), in.note());
    return ResponseEntity.created(URI.create("/v1/orders/" + o.id())).body(view(o));
  }

  @GetMapping("/{id}")
  public OrderV1 get(@PathVariable String id) {
    return view(store.find(id).orElseThrow(() -> Validation.notFound(id)));
  }
}
