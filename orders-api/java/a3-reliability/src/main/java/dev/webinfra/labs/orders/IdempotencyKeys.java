package dev.webinfra.labs.orders;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Stores the outcome of each Idempotency-Key so a retry gets the original response back.
 *
 *   first request with a key   -> insert "in_progress", do the work, store the response
 *   same key, same request     -> replay the stored response (or 409 while still in progress)
 *   same key, different request-> 422: the client reused a key by mistake
 */
@Component
public class IdempotencyKeys {
  /** A stored response, ready to send again. */
  public record Stored(int status, String body, String location) {}

  private final JdbcClient db;

  public IdempotencyKeys(JdbcClient db) {
    this.db = db;
  }

  /** Returns null if this request is the first with the key (so the caller should do the work). */
  public Stored begin(String key, String fingerprint) {
    int inserted = db.sql("""
            INSERT INTO idempotency_keys (key, fingerprint, state) VALUES (?, ?, 'in_progress')
            ON CONFLICT (key) DO NOTHING""")
        .params(key, fingerprint).update();
    if (inserted == 1) return null;

    var row = db.sql("SELECT fingerprint, state, response_status, response_body, location FROM idempotency_keys WHERE key = ?")
        .param(key)
        .query((rs, n) -> Map.of(
            "fingerprint", rs.getString("fingerprint"), "state", rs.getString("state"),
            "status", String.valueOf(rs.getInt("response_status")),
            "body", String.valueOf(rs.getString("response_body")), "location", String.valueOf(rs.getString("location"))))
        .single();
    if (!row.get("fingerprint").equals(fingerprint)) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
          "this Idempotency-Key was already used with a different request body or URL");
    }
    if (row.get("state").equals("in_progress")) {
      throw new ApiException(HttpStatus.CONFLICT, "idempotency-key-in-progress",
          "the original request is still being processed; retry shortly", Map.of(), Map.of("Retry-After", "1"));
    }
    return new Stored(Integer.parseInt(row.get("status")), row.get("body"), row.get("location"));
  }

  public void complete(String key, int status, String body, String location) {
    db.sql("UPDATE idempotency_keys SET state = 'completed', response_status = ?, response_body = ?, location = ? WHERE key = ?")
        .params(status, body, location, key).update();
  }

  /** The work failed before anything changed: forget the key so the client can try again. */
  public void abandon(String key) {
    db.sql("DELETE FROM idempotency_keys WHERE key = ?").param(key).update();
  }
}
