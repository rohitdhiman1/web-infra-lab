package dev.webinfra.labs.orders;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

/** The representation the API sends back. The version isn't in the body; it travels as the ETag. */
public record Order(
    String id, String customer, String currency, List<OrderItem> items,
    long total, String status, Instant createdAt, @JsonIgnore int version, @JsonIgnore String owner) {

  public record OrderItem(String sku, Integer quantity, Long unitPrice) {}

  /** A strong ETag: quoted, changes whenever the order changes. */
  public String etag() {
    return "\"" + version + "\"";
  }

  OrderInput input() {
    return new OrderInput(customer, currency, items, status);
  }
}
