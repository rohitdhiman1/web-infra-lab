package dev.webinfra.labs.orders;

import org.springframework.http.HttpStatus;

/** Public ids are opaque strings ("ord_12"); the database uses numbers. */
final class Ids {
  private Ids() {}

  static long order(String id) {
    try {
      if (id.startsWith("ord_")) return Long.parseLong(id.substring(4));
    } catch (NumberFormatException ignored) {
      // fall through
    }
    throw notFound(id);
  }

  static ApiException notFound(String id) {
    return new ApiException(HttpStatus.NOT_FOUND, "order-not-found", "order " + id + " not found");
  }
}
