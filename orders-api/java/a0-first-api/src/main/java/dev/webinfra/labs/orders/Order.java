package dev.webinfra.labs.orders;

import java.time.Instant;
import java.util.List;

/**
 * The representation of an order that the API sends back.
 * Money is an integer number of minor units (cents), never a float.
 */
public record Order(
    String id,
    String customer,
    String currency,
    List<OrderItem> items,
    long total,
    String status,
    Instant createdAt) {

  public record OrderItem(String sku, int quantity, long unitPrice) {}

  static long totalOf(List<OrderItem> items) {
    return items.stream().mapToLong(i -> i.quantity() * i.unitPrice()).sum();
  }
}
