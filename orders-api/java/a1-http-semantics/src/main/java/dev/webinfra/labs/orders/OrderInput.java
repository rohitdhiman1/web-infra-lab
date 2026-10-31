package dev.webinfra.labs.orders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-settable fields. POST sends customer, currency and items (status starts as "pending");
 * PUT must send all four. The server owns id, total and createdAt and ignores them if sent.
 */
public record OrderInput(String customer, String currency, List<Order.OrderItem> items, String status) {
  static final Set<String> STATUSES = Set.of("pending", "cancelled");

  /** Field -> problem. Empty means valid. Shape errors here are 422, not 400: the JSON parsed fine. */
  Map<String, String> problems(boolean statusRequired) {
    Map<String, String> p = new LinkedHashMap<>();
    if (customer == null || customer.isBlank()) p.put("customer", "is required");
    if (currency == null || !currency.matches("[A-Z]{3}")) p.put("currency", "must be a 3-letter ISO 4217 code, e.g. EUR");
    if (items == null || items.isEmpty()) {
      p.put("items", "must contain at least one item");
    } else {
      for (int i = 0; i < items.size(); i++) {
        Order.OrderItem it = items.get(i);
        if (it == null || it.sku() == null || it.sku().isBlank()) p.put("items[" + i + "].sku", "is required");
        if (it == null || it.quantity() == null || it.quantity() < 1) p.put("items[" + i + "].quantity", "must be at least 1");
        if (it == null || it.unitPrice() == null || it.unitPrice() < 0) p.put("items[" + i + "].unitPrice", "must be 0 or more");
      }
    }
    if (status == null ? statusRequired : !STATUSES.contains(status)) p.put("status", "must be pending or cancelled");
    return p;
  }
}
