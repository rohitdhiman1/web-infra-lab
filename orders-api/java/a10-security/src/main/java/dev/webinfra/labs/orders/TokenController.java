package dev.webinfra.labs.orders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stand-in for an identity provider (Keycloak, Auth0, Entra ID, Okta), so the lab runs offline.
 * A real one authenticates the user first (password, passkey, SSO) and runs an OAuth flow;
 * this one just trusts the user name. The API side, verifying the token, is exactly what you'd
 * write against a real provider.
 */
@RestController
public class TokenController {
  record User(String role, String scope) {}

  static final Map<String, User> USERS = Map.of(
      "alice", new User("customer", "orders:read orders:write"),
      "bob", new User("customer", "orders:read orders:write"),
      "sam", new User("support", "orders:read orders:read:any"),
      "ana", new User("admin", "orders:read orders:read:any orders:write refunds:write"));

  private final JwtEncoder encoder;
  private final SigningKeys keys;
  private final String issuer;
  private final String audience;
  private final long ttl;

  TokenController(JwtEncoder encoder, SigningKeys keys, @Value("${auth.issuer}") String issuer,
                  @Value("${auth.audience}") String audience, @Value("${auth.token-ttl-seconds}") long ttl) {
    this.encoder = encoder;
    this.keys = keys;
    this.issuer = issuer;
    this.audience = audience;
    this.ttl = ttl;
  }

  record TokenRequest(String user) {}

  @PostMapping(path = "/token", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Map<String, Object>> token(@RequestBody TokenRequest body) {
    User u = body.user() == null ? null : USERS.get(body.user());
    if (u == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "unknown-user", "no such user: " + body.user());
    }
    Instant now = Instant.now();
    var claims = JwtClaimsSet.builder()
        .issuer(issuer)                        // iss: who made it
        .audience(List.of(audience))           // aud: which API it's for
        .subject(body.user())                  // sub: who it's about
        .issuedAt(now)
        .expiresAt(now.plusSeconds(ttl))       // short-lived: there's no way to revoke a JWT early
        .id(UUID.randomUUID().toString())      // jti
        .claim("scope", u.scope())
        .claim("role", u.role())
        .build();
    var header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.keyId()).build();
    String jwt = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())   // tokens must never sit in a cache
        .body(Map.of("access_token", jwt, "token_type", "Bearer", "expires_in", ttl));
  }

  /** The public key, in the format verifiers fetch. Publishing it is safe: it can only verify. */
  @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> jwks() {
    return keys.publicJwks().toJSONObject();
  }
}
