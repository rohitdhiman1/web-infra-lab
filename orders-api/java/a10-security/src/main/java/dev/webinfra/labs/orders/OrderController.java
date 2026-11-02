package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every handler does the same two things before any work:
 *   1. function level: caller.require(scope)      -> 403 if the token lacks the scope
 *   2. object level:   visible(caller, order, ...) -> 404 if this order isn't theirs
 * Spring Security has already done authentication (401) before we get here.
 */
@RestController
public class OrderController {
  record Order(String id, String customerId, String status, String total) {}

  private final Map<String, Order> orders = new ConcurrentHashMap<>(Map.of(
      "ord_1", new Order("ord_1", "alice", "placed", "42.00"),
      "ord_2", new Order("ord_2", "bob", "placed", "17.50")));
  private final AtomicInteger refunds = new AtomicInteger();

  /** Your own orders only. The filter comes from the token, never from a query parameter. */
  @GetMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, List<Order>> mine(Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:read");
    return Map.of("data", sorted(orders.values().stream().filter(o -> o.customerId().equals(c.id())).toList()));
  }

  @GetMapping(path = "/orders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  Order get(@PathVariable String id, Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:read");
    return find(c, id, false);
  }

  /**
   * Customers may change exactly one thing: status, to "cancelled". Anything else in the body
   * (customerId, total) is refused, not silently applied: that's property-level authorisation,
   * the defence against mass assignment.
   */
  @PatchMapping(path = "/orders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  Order patch(@PathVariable String id, @RequestBody Map<String, Object> body, Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:write");
    Order o = find(c, id, true);
    var readOnly = new LinkedHashMap<String, String>();
    body.keySet().stream().filter(k -> !k.equals("status")).sorted()
        .forEach(k -> readOnly.put("#/" + k, "cannot be changed"));
    if (!readOnly.isEmpty()) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "read-only-field",
          "only status may be changed", readOnly, Map.of());
    }
    if (!"cancelled".equals(body.get("status"))) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "status can only be set to cancelled",
          Map.of("#/status", "must be \"cancelled\""), Map.of());
    }
    Order cancelled = new Order(o.id(), o.customerId(), "cancelled", o.total());
    orders.put(o.id(), cancelled);
    return cancelled;
  }

  /** Refunds are a staff action: the scope alone decides, and staff may refund any order. */
  @PostMapping(path = "/orders/{id}/refunds", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Map<String, String>> refund(@PathVariable String id, Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("refunds:write");
    Order o = find(c, id, true);
    String refundId = "ref_" + refunds.incrementAndGet();
    return ResponseEntity.created(URI.create("/orders/" + o.id() + "/refunds/" + refundId))
        .body(Map.of("id", refundId, "orderId", o.id(), "amount", o.total(), "status", "pending"));
  }

  /** Everyone's orders: support, admins and back-office services only. */
  @GetMapping(path = "/admin/orders", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, List<Order>> all(Authentication auth) {
    Caller.from(auth).require("orders:read:any");
    return Map.of("data", sorted(List.copyOf(orders.values())));
  }

  /**
   * Object-level authorisation. "Doesn't exist" and "isn't yours" give the same 404, word for word,
   * so a caller can't probe for other people's order ids.
   */
  private Order find(Caller c, String id, boolean write) {
    Order o = orders.get(id);
    boolean mayAccess = o != null && (o.customerId().equals(c.id())
        || (write ? "admin".equals(c.role()) : c.has("orders:read:any")));
    if (!mayAccess) {
      throw new ApiException(HttpStatus.NOT_FOUND, "order-not-found", "order " + id + " not found");
    }
    return o;
  }

  private static List<Order> sorted(List<Order> list) {
    return list.stream().sorted(Comparator.comparing(Order::id)).toList();
  }
}
