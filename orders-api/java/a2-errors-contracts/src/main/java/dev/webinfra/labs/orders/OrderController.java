package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * A1: the full method set with deliberate status codes.
 * Spring already handles HEAD (as GET without a body), 405 + Allow, and 406 for us.
 */
@RestController
@RequestMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {
  static final String MERGE_PATCH = "application/merge-patch+json";
  private final OrderStore store;
  private final ObjectMapper json;

  public OrderController(OrderStore store, ObjectMapper json) {
    this.store = store;
    this.json = json;
  }

  @GetMapping
  public Map<String, List<Order>> list() {
    return Map.of("data", store.all());
  }

  // POST: not idempotent. Each call creates a new order with a new id.
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ApiResponse(responseCode = "201", description = "Created; Location points at the new order")
  @ApiResponse(responseCode = "422", description = "Validation failed (application/problem+json)")
  public ResponseEntity<Order> create(@RequestBody OrderInput body) {
    var problems = body.problems(false);
    problems.remove("#/status"); // status can't be chosen on create
    if (!problems.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", problems);
    Order order = store.create(new OrderInput(body.customer(), body.currency(), body.items(), null));
    return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
  }

  @GetMapping("/{id}")
  public Order get(@PathVariable String id) {
    return existing(id);
  }

  // PUT: replace every client-settable field. Idempotent: sending it twice leaves the same state.
  @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Order replace(@PathVariable String id, @RequestBody OrderInput body) {
    Order current = existing(id);
    var problems = body.problems(true);
    if (!problems.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", problems);
    return apply(current, body);
  }

  // PATCH with JSON Merge Patch (RFC 7396): only the fields you send change.
  // Declaring consumes makes Spring answer 415 (with Accept-Patch) for any other Content-Type.
  @PatchMapping(path = "/{id}", consumes = MERGE_PATCH)
  public Order patch(@PathVariable String id, @RequestBody String raw) {
    Order current = existing(id);
    Map<String, Object> patch;
    try {
      patch = json.readValue(raw, Map.class);
    } catch (Exception e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "malformed-body", "request body is not a JSON object");
    }
    for (String field : List.of("customer", "currency", "items", "status")) {
      if (patch.containsKey(field) && patch.get(field) == null) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", Map.of("#/" + field, "is required and can't be removed"));
      }
    }
    OrderInput merged;
    try {
      // Merge: start from the current fields, overwrite with the ones in the patch.
      Map<String, Object> base = json.convertValue(current.input(), Map.class);
      base.putAll(patch);
      base.keySet().retainAll(List.of("customer", "currency", "items", "status"));
      merged = json.convertValue(base, OrderInput.class);
    } catch (Exception e) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "request body has the wrong shape");
    }
    var problems = merged.problems(true);
    if (!problems.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed", problems);
    return apply(current, merged);
  }

  // DELETE: idempotent in effect. The first call removes it (204); later calls find nothing (404).
  @DeleteMapping("/{id}")
  @ApiResponse(responseCode = "204", description = "Deleted")
  @ApiResponse(responseCode = "404", description = "No such order (application/problem+json)")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    if (!store.delete(id)) throw new ApiException(HttpStatus.NOT_FOUND, "order-not-found", "order " + id + " not found");
    return ResponseEntity.noContent().build();
  }

  /** A cancelled order is final. Repeating the same change is a no-op (200), so retries are safe. */
  private Order apply(Order current, OrderInput next) {
    if (next.equals(current.input())) return current;
    if (current.status().equals("cancelled")) {
      throw new ApiException(HttpStatus.CONFLICT, "order-cancelled", "order " + current.id() + " is cancelled and can't be changed");
    }
    Order updated = Order.from(current.id(), next, next.status(), current.createdAt());
    store.save(updated);
    return updated;
  }

  private Order existing(String id) {
    return store.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "order-not-found", "order " + id + " not found"));
  }
}
