package dev.webinfra.labs.orders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * A5: GET /orders, newest first, one page at a time.
 *   ?limit=1..100 (default 20)   ?status=pending|cancelled
 *   ?cursor=<opaque>  keyset pagination (use this)
 *   ?offset=<n>       offset pagination (kept to show why cursors win)
 * The body's "next" and the Link header point at the next page; both are absent on the last page.
 * A10: you see your own orders. Staff with orders:read:any see everyone's.
 */
@RestController
public class OrderListController {
  static final Set<String> STATUSES = Set.of("pending", "paying", "paid", "cancelled");
  private final OrderRepository orders;

  public OrderListController(OrderRepository orders) {
    this.orders = orders;
  }

  @GetMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> list(
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String offset,
      @RequestParam(required = false) String status,
      Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:read");
    // The filter comes from the token, never from a query parameter like ?owner=.
    String owner = c.has("orders:read:any") ? null : c.id();
    int size = intParam("limit", limit, 20, 1, 100);
    if (status != null && !STATUSES.contains(status)) throw bad("status must be one of " + STATUSES);
    if (cursor != null && offset != null) throw bad("send cursor or offset, not both");

    // Ask for one extra row: if it comes back, there's a next page.
    List<Order> rows;
    String next = null;
    if (offset != null) {
      int start = intParam("offset", offset, 0, 0, Integer.MAX_VALUE);
      rows = orders.pageAtOffset(owner, start, status, size + 1);
      if (rows.size() > size) next = link(size, status, "offset", String.valueOf(start + size));
    } else {
      rows = orders.pageAfter(owner, cursor == null ? null : decode(cursor), status, size + 1);
      if (rows.size() > size) next = link(size, status, "cursor", encode(rows.get(size - 1)));
    }
    List<Order> page = rows.subList(0, Math.min(size, rows.size()));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", page);
    body.put("next", next);
    var response = ResponseEntity.ok();
    if (next != null) response.header("Link", "<" + next + ">; rel=\"next\"");
    return response.body(body);
  }

  /** Cursors are opaque to clients: base64url of the last id seen. Clients must not build or parse them. */
  static String encode(Order last) {
    String raw = "id:" + last.id().substring("ord_".length());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static long decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (raw.startsWith("id:")) return Long.parseLong(raw.substring(3));
    } catch (IllegalArgumentException ignored) {
      // fall through
    }
    throw bad("cursor is not valid; use the next link from the previous page");
  }

  static String link(int limit, String status, String name, String value) {
    var b = UriComponentsBuilder.fromPath("/orders").queryParam("limit", limit);
    if (status != null) b.queryParam("status", status);
    return b.queryParam(name, value).build().toUriString();
  }

  static int intParam(String name, String raw, int dflt, int min, int max) {
    if (raw == null) return dflt;
    try {
      int v = Integer.parseInt(raw);
      if (v >= min && v <= max) return v;
    } catch (NumberFormatException ignored) {
      // fall through
    }
    throw bad(name + " must be a whole number from " + min + " to " + max);
  }

  static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "invalid-parameter", message);
  }
}
