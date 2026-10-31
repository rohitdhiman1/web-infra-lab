package dev.webinfra.labs.orders;

import java.time.Instant;
import java.util.List;

/** The representation the API sends back. Money is integer minor units (cents). */
public record Order(
    String id, String customer, String currency, List<OrderItem> items,
    long total, String status, Instant createdAt) {

  public record OrderItem(String sku, Integer quantity, Long unitPrice) {}

  static Order from(String id, OrderInput in, String status, Instant createdAt) {
    long total = in.items().stream().mapToLong(i -> i.quantity() * i.unitPrice()).sum();
    return new Order(id, in.customer(), in.currency(), List.copyOf(in.items()), total, status, createdAt);
  }

  /** The fields a client is allowed to set: what PUT replaces and PATCH merges into. */
  OrderInput input() {
    return new OrderInput(customer, currency, items, status);
  }
}
