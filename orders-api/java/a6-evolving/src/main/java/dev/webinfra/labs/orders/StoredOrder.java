package dev.webinfra.labs.orders;

import java.time.Instant;
import java.util.List;

/**
 * How the server stores an order. Two API versions read and write it, each with its own
 * representation: the storage model and the contract are deliberately separate things.
 */
public record StoredOrder(String id, String customerEmail, String customerName, String currency,
                          List<Item> items, String note, long total, String status, Instant createdAt) {
  public record Item(String sku, Integer quantity, Long unitPrice) {}
}
