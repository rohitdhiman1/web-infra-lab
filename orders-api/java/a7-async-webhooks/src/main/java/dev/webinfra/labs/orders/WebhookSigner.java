package dev.webinfra.labs.orders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies webhooks the way the Standard Webhooks spec (standardwebhooks.com) does:
 *   webhook-id:        msg_...      unique per event; the same on every retry, so receivers can dedupe
 *   webhook-timestamp: Unix seconds  lets receivers reject old (replayed) messages
 *   webhook-signature: v1,<base64>   HMAC-SHA256 over "{id}.{timestamp}.{body}" with the shared secret
 * Secrets look like "whsec_" + base64(random bytes).
 */
public final class WebhookSigner {
  static final long TOLERANCE_SECONDS = 300;

  private WebhookSigner() {}

  public static String newSecret() {
    byte[] key = new byte[24];
    new SecureRandom().nextBytes(key);
    return "whsec_" + Base64.getEncoder().encodeToString(key);
  }

  public static String sign(String secret, String id, long timestamp, String body) {
    try {
      byte[] key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] sig = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
      return "v1," + Base64.getEncoder().encodeToString(sig);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** What a receiver runs before trusting a webhook. */
  public static boolean verify(String secret, String id, String timestamp, String signatureHeader, String body,
                               long nowSeconds) {
    long ts;
    try {
      ts = Long.parseLong(timestamp);
    } catch (NumberFormatException e) {
      return false;
    }
    if (Math.abs(nowSeconds - ts) > TOLERANCE_SECONDS) return false;   // too old (or from the future): a replay?
    byte[] expected = sign(secret, id, ts, body).getBytes(StandardCharsets.UTF_8);
    // The header may hold several space-separated signatures (during secret rotation).
    for (String candidate : signatureHeader.split(" ")) {
      // Constant-time comparison: don't leak how many bytes matched.
      if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) return true;
    }
    return false;
  }
}
