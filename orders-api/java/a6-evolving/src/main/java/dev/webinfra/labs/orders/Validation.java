package dev.webinfra.labs.orders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

final class Validation {
  private Validation() {}

  static void check(String email, String emailPointer, String currency, List<StoredOrder.Item> items) {
    Map<String, String> p = new LinkedHashMap<>();
    if (email == null || !email.contains("@")) p.put(emailPointer, "must be an email address");
    if (currency == null || !currency.matches("[A-Z]{3}")) p.put("#/currency", "must be a 3-letter ISO 4217 code");
    if (items == null || items.isEmpty()) p.put("#/items", "must contain at least one item");
    else for (int i = 0; i < items.size(); i++) {
      var it = items.get(i);
      if (it.sku() == null || it.quantity() == null || it.quantity() < 1 || it.unitPrice() == null || it.unitPrice() < 0) {
        p.put("#/items/" + i, "needs a sku, a quantity of at least 1 and a unitPrice of 0 or more");
      }
    }
    if (!p.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", p);
  }

  static ApiException notFound(String id) {
    return new ApiException(HttpStatus.NOT_FOUND, "order-not-found", "order " + id + " not found");
  }
}
