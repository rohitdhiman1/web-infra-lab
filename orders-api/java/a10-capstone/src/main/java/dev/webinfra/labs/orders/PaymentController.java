package dev.webinfra.labs.orders;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /orders/{id}/payments with an Idempotency-Key header.
 * POST isn't idempotent by itself; the key makes this one safe to retry.
 */
@RestController
public class PaymentController {
  private final OrderRepository orders;
  private final IdempotencyKeys keys;
  private final FakeGateway gateway;
  private final ObjectMapper json;

  public PaymentController(OrderRepository orders, IdempotencyKeys keys, FakeGateway gateway, ObjectMapper json) {
    this.orders = orders;
    this.keys = keys;
    this.gateway = gateway;
    this.json = json;
  }

  @PostMapping(path = "/orders/{id}/payments", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> pay(@PathVariable String id,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                    @RequestBody String raw, HttpServletRequest request) {
    if (key == null || key.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "idempotency-key-missing",
          "send an Idempotency-Key header (a fresh UUID per payment) so retries can't charge twice");
    }
    // Same key must mean same request: fingerprint the method, path and body.
    String fingerprint = sha256(request.getMethod() + " " + request.getRequestURI() + "\n" + raw);

    IdempotencyKeys.Stored stored = keys.begin(key, fingerprint);   // 422 / 409 thrown from here
    if (stored != null) {
      return ResponseEntity.status(stored.status())
          .header("Location", stored.location())
          .header("Idempotent-Replayed", "true")
          .contentType(MediaType.APPLICATION_JSON)
          .body(stored.body());
    }

    try {
      Payment payment = charge(id, raw, key);
      String body = json.writeValueAsString(payment);
      String location = "/orders/" + id + "/payments/" + payment.id();
      keys.complete(key, 201, body, location);
      return ResponseEntity.status(HttpStatus.CREATED).header("Location", location)
          .contentType(MediaType.APPLICATION_JSON).body(body);
    } catch (RuntimeException e) {
      keys.abandon(key);   // nothing was stored: let the client retry with the same key
      throw e;
    }
  }

  private Payment charge(String id, String raw, String key) {
    long orderId = Ids.order(id);
    Order order = orders.find(orderId).orElseThrow(() -> Ids.notFound(id));
    JsonNode body;
    try {
      body = json.readTree(raw);
    } catch (Exception e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "malformed-body", "request body is not valid JSON");
    }
    JsonNode amount = body.get("amount");
    if (amount == null || !amount.canConvertToLong() || amount.asLong() != order.total()) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed",
          Map.of("#/amount", "must equal the order total, " + order.total()));
    }
    if (!orders.claimForPayment(orderId)) {
      throw new ApiException(HttpStatus.CONFLICT, "order-not-payable", "order " + id + " is already paid, being paid, or cancelled");
    }
    try {
      String chargeId = gateway.charge(key, id, order.total());
      return orders.recordPayment(orderId, order.total(), order.currency(), chargeId);
    } catch (RuntimeException e) {
      orders.releaseClaim(orderId);
      throw new ApiException(HttpStatus.BAD_GATEWAY, "payment-failed", "the payment gateway failed: " + e.getMessage());
    }
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
