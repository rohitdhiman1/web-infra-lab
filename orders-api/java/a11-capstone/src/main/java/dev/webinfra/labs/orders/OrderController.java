package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The capstone orders resource (from A4): every order carries an ETag.
 *   GET   + If-None-Match  -> 304 Not Modified if unchanged (saves the body)
 *   PATCH + If-Match       -> 412 if someone changed it since you read it (no lost updates)
 *   PATCH without If-Match -> 428: this API insists on conditional writes
 * A10: every handler checks the scope (403), then whether this caller may see this order (404).
 */
@RestController
@RequestMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {
  static final String MERGE_PATCH = "application/merge-patch+json";
  // Clients may keep a copy but must check it's still current before using it.
  static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();
  private final OrderRepository orders;
  private final ObjectMapper json;

  public OrderController(OrderRepository orders, ObjectMapper json) {
    this.orders = orders;
    this.json = json;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Order> create(@RequestBody OrderInput body, Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:write");
    var problems = body.problems(false);
    problems.remove("#/status");
    if (!problems.isEmpty()) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", problems);
    }
    Order order = orders.create(body, c.id());
    return ResponseEntity.created(URI.create("/orders/" + order.id())).eTag(order.etag()).body(order);
  }

  @GetMapping("/{id}")
  public ResponseEntity<Order> get(@PathVariable String id,
                                   @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
                                   Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:read");
    Order order = visible(orders, c, id, false);
    if (matches(ifNoneMatch, order.etag())) {
      // Unchanged: no body, just the validators. The client reuses its copy.
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(order.etag()).cacheControl(REVALIDATE).build();
    }
    return ResponseEntity.ok().eTag(order.etag()).cacheControl(REVALIDATE).body(order);
  }

  @PatchMapping(path = "/{id}", consumes = MERGE_PATCH)
  public ResponseEntity<Order> patch(@PathVariable String id,
                                     @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                     @RequestBody String raw, Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:write");
    Order current = visible(orders, c, id, true);
    if (current.status().equals("paid") || current.status().equals("paying")) {
      throw new ApiException(HttpStatus.CONFLICT, "order-locked", "order " + id + " is " + current.status() + " and can't be changed");
    }
    if (ifMatch == null) {
      throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition-required",
          "send If-Match with the ETag you last read, so concurrent edits can't overwrite each other");
    }
    if (!matches(ifMatch, current.etag())) {
      throw new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition-failed",
          "order " + id + " is now at version " + current.etag() + "; GET it again and reapply your change");
    }
    OrderInput merged = merge(current, raw);
    // Re-check in the UPDATE itself: another writer may have slipped in since we read `current`.
    if (!orders.updateIfVersion(Ids.order(id), current.version(), merged)) {
      throw new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition-failed",
          "order " + id + " changed while your update was being applied; GET it again");
    }
    Order updated = orders.find(Ids.order(id)).orElseThrow(() -> Ids.notFound(id));
    return ResponseEntity.ok().eTag(updated.etag()).cacheControl(REVALIDATE).body(updated);
  }

  /** If-Match / If-None-Match: "*" or a comma-separated list of ETags. Weak tags (W/) never match If-Match. */
  static boolean matches(String header, String etag) {
    if (header == null) return false;
    for (String tag : header.split(",")) {
      String t = tag.trim();
      if (t.equals("*") || t.equals(etag)) return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private OrderInput merge(Order current, String raw) {
    Map<String, Object> patch;
    try {
      patch = json.readValue(raw, Map.class);
    } catch (Exception e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "malformed-body", "request body is not a JSON object");
    }
    Map<String, Object> base = json.convertValue(current.input(), Map.class);
    base.putAll(patch);
    base.keySet().retainAll(List.of("customer", "currency", "items", "status"));
    OrderInput merged = json.convertValue(base, OrderInput.class);
    var problems = merged.problems(true);
    if (!problems.isEmpty()) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", problems);
    }
    return merged;
  }

  @GetMapping("/{id}/payments")
  public Map<String, Object> payments(@PathVariable String id, Authentication auth) {
    Caller c = Caller.from(auth);
    c.require("orders:read");
    visible(orders, c, id, false);
    return Map.of("data", orders.payments(Ids.order(id)));
  }

  /**
   * Object-level authorisation. Owners may read and write their orders; staff with orders:read:any
   * may read anyone's; only admins may write anyone's. Everyone else gets the same 404 as a missing order.
   */
  static Order visible(OrderRepository orders, Caller c, String id, boolean write) {
    boolean anyOwner = write ? "admin".equals(c.role()) : c.has("orders:read:any");
    return orders.findVisible(Ids.order(id), c.id(), anyOwner).orElseThrow(() -> Ids.notFound(id));
  }
}
