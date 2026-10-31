package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A7: work that takes too long for one request.
 *   POST /refunds        -> 202 Accepted + Location of a status resource + Retry-After
 *   GET  /refunds/{id}   -> the status ("pending" with Retry-After, then "succeeded")
 *   POST /webhooks       -> subscribe a URL; the response holds the signing secret (shown once)
 */
@RestController
public class RefundController {
  public record NewRefund(String orderId, Long amount) {}

  public record NewWebhook(String url) {}

  private final RefundService refunds;

  public RefundController(RefundService refunds) {
    this.refunds = refunds;
  }

  @PostMapping(path = "/refunds", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RefundService.Refund> start(@RequestBody NewRefund in) {
    if (in.orderId() == null || in.amount() == null || in.amount() < 1) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed",
          Map.of("#/amount", "orderId and a positive amount are required"));
    }
    var r = refunds.start(in.orderId(), in.amount());
    // 202: accepted, not done. The Location is where to check on it, not the finished result.
    return ResponseEntity.accepted().location(URI.create("/refunds/" + r.id())).header("Retry-After", "1").body(r);
  }

  @GetMapping(path = "/refunds/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RefundService.Refund> status(@PathVariable String id) {
    var r = refunds.find(id).orElseThrow(() ->
        new ApiException(HttpStatus.NOT_FOUND, "refund-not-found", "refund " + id + " not found"));
    var res = ResponseEntity.ok();
    if (r.status().equals("pending")) res.header("Retry-After", "1");   // tell pollers how often to ask
    return res.body(r);
  }

  @PostMapping(path = "/webhooks", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RefundService.Subscription> subscribe(@RequestBody NewWebhook in) {
    if (in.url() == null || !(in.url().startsWith("https://") || in.url().startsWith("http://localhost")
        || in.url().startsWith("http://127.0.0.1"))) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "validation failed",
          Map.of("#/url", "must be an https URL (plain http only for localhost)"));
    }
    var s = refunds.subscribe(in.url());
    return ResponseEntity.created(URI.create("/webhooks/" + s.id())).body(s);
  }
}
