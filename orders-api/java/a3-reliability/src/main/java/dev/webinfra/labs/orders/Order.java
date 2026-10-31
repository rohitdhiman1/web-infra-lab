package dev.webinfra.labs.orders;

import java.time.Instant;
import java.util.List;

/** The representation the API sends back. Money is integer minor units (cents). */
public record Order(
    String id, String customer, String currency, List<OrderItem> items,
    long total, String status, Instant createdAt) {

  public record OrderItem(String sku, Integer quantity, Long unitPrice) {}
}
