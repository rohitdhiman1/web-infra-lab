package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)   // fresh orders per test
class SecurityTest {
  static final String WAREHOUSE_KEY = "wh_test_4f9c2a7d1e8b6035";

  @LocalServerPort int port;
  @Autowired JwtEncoder encoder;    // the server's own signing key, to mint unusual tokens
  final HttpClient http = HttpClient.newHttpClient();
  final ObjectMapper json = new ObjectMapper();

  HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    if (body != null) b.header("Content-Type", "application/json");
    if (headers.length > 0) b.headers(headers);
    return http.send(b.build(), BodyHandlers.ofString());
  }

  HttpResponse<String> as(String user, String method, String path, String body) throws Exception {
    return send(method, path, body, "Authorization", "Bearer " + token(user));
  }

  String token(String user) throws Exception {
    var res = send("POST", "/token", "{\"user\":\"" + user + "\"}");
    assertThat(res.statusCode()).isEqualTo(200);
    return json.readTree(res.body()).get("access_token").asString();
  }

  /** A token signed by the real key, with claims the issuer would never produce. */
  String forged(String issuer, String audience, Instant exp) {
    var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject("alice")
        .issuedAt(exp.minusSeconds(300)).expiresAt(exp).claim("scope", "orders:read orders:write").build();
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.ES256).build(), claims))
        .getTokenValue();
  }

  JsonNode body(HttpResponse<String> res) {
    return json.readTree(res.body());
  }

  static String b64(String s) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  // ---- authentication: 401 ----

  @Test void noCredentialIs401WithAChallenge() throws Exception {
    var res = send("GET", "/orders/ord_1", null);
    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(res.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"orders-api\"");
    assertThat(res.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
    assertThat(body(res).get("type").asString()).endsWith("/unauthenticated");
  }

  @Test void unknownPathsAre401TooSoTheyRevealNothing() throws Exception {
    assertThat(send("GET", "/no-such-thing", null).statusCode()).isEqualTo(401);
  }

  @Test void tokenResponseIsNeverCached() throws Exception {
    var res = send("POST", "/token", "{\"user\":\"alice\"}");
    assertThat(res.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    assertThat(body(res).get("token_type").asString()).isEqualTo("Bearer");
    assertThat(send("POST", "/token", "{\"user\":\"mallory\"}").statusCode()).isEqualTo(400);
  }

  @Test void expiredTokenIs401InvalidToken() throws Exception {
    String expired = forged("https://id.orders.test", "orders-api", Instant.now().minusSeconds(600));
    var res = send("GET", "/orders/ord_1", null, "Authorization", "Bearer " + expired);
    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(res.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("error=\"invalid_token\"");
    assertThat(body(res).get("type").asString()).endsWith("/invalid-token");
  }

  @Test void wrongIssuerOrAudienceIs401() throws Exception {
    Instant later = Instant.now().plusSeconds(300);
    for (String t : List.of(forged("https://evil.test", "orders-api", later), forged("https://id.orders.test", "billing-api", later))) {
      assertThat(send("GET", "/orders/ord_1", null, "Authorization", "Bearer " + t).statusCode()).isEqualTo(401);
    }
  }

  @Test void tamperedOrUnsignedTokenIs401() throws Exception {
    String[] parts = token("bob").split("\\.");
    // Swap in a payload claiming to be alice, keeping bob's signature.
    String payload = b64("{\"iss\":\"https://id.orders.test\",\"aud\":\"orders-api\",\"sub\":\"alice\",\"scope\":\"orders:read\",\"exp\":"
        + Instant.now().plusSeconds(300).getEpochSecond() + "}");
    String tampered = parts[0] + "." + payload + "." + parts[2];
    String unsigned = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + payload + ".";
    for (String t : List.of(tampered, unsigned)) {
      assertThat(send("GET", "/orders/ord_1", null, "Authorization", "Bearer " + t).statusCode()).isEqualTo(401);
    }
  }

  @Test void unknownApiKeyIs401() throws Exception {
    var res = send("GET", "/admin/orders", null, "X-API-Key", "wh_test_guessed");
    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(res.headers().firstValue("WWW-Authenticate")).hasValue("ApiKey realm=\"orders-api\"");
  }

  @Test void jwksPublishesOnlyThePublicKey() throws Exception {
    JsonNode key = body(send("GET", "/.well-known/jwks.json", null)).get("keys").get(0);
    assertThat(key.get("kty").asString()).isEqualTo("EC");
    assertThat(key.has("d")).isFalse();   // "d" is the private part of an EC key
  }

  // ---- function-level authorisation: 403 ----

  @Test void missingScopeIs403WithTheScopeNamed() throws Exception {
    var res = as("alice", "POST", "/orders/ord_1/refunds", null);
    assertThat(res.statusCode()).isEqualTo(403);
    assertThat(res.headers().firstValue("WWW-Authenticate"))
        .hasValue("Bearer error=\"insufficient_scope\", scope=\"refunds:write\"");
    assertThat(as("bob", "GET", "/admin/orders", null).statusCode()).isEqualTo(403);
  }

  // ---- object-level authorisation: 404 ----

  @Test void bobCannotSeeOrCancelAlicesOrder() throws Exception {
    var read = as("bob", "GET", "/orders/ord_1", null);
    var missing = as("bob", "GET", "/orders/ord_999", null);
    assertThat(read.statusCode()).isEqualTo(404);
    // Identical to an order that doesn't exist, so ids can't be probed.
    assertThat(body(read).get("detail").asString()).isEqualTo("order ord_1 not found");
    assertThat(body(missing).get("type")).isEqualTo(body(read).get("type"));
    assertThat(as("bob", "PATCH", "/orders/ord_1", "{\"status\":\"cancelled\"}").statusCode()).isEqualTo(404);
  }

  @Test void aliceReadsAndCancelsHerOwnOrder() throws Exception {
    assertThat(body(as("alice", "GET", "/orders/ord_1", null)).get("customerId").asString()).isEqualTo("alice");
    var list = body(as("alice", "GET", "/orders", null)).get("data");
    assertThat(list.size()).isEqualTo(1);
    var res = as("alice", "PATCH", "/orders/ord_1", "{\"status\":\"cancelled\"}");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(body(res).get("status").asString()).isEqualTo("cancelled");
  }

  @Test void supportReadsAnyOrderButCannotCancelIt() throws Exception {
    assertThat(as("sam", "GET", "/orders/ord_1", null).statusCode()).isEqualTo(200);
    assertThat(as("sam", "GET", "/admin/orders", null).statusCode()).isEqualTo(200);
    assertThat(as("sam", "PATCH", "/orders/ord_1", "{\"status\":\"cancelled\"}").statusCode()).isEqualTo(403);
  }

  @Test void adminRefundsAnyOrder() throws Exception {
    var res = as("ana", "POST", "/orders/ord_1/refunds", null);
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.headers().firstValue("Location")).hasValue("/orders/ord_1/refunds/ref_1");
    assertThat(as("ana", "POST", "/orders/ord_999/refunds", null).statusCode()).isEqualTo(404);
  }

  // ---- property-level authorisation: 422 ----

  @Test void ownerCannotChangeReadOnlyFields() throws Exception {
    var res = as("alice", "PATCH", "/orders/ord_1", "{\"status\":\"cancelled\",\"total\":\"0.01\"}");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(body(res).get("errors").get(0).get("pointer").asString()).isEqualTo("#/total");
    assertThat(body(as("alice", "GET", "/orders/ord_1", null)).get("status").asString()).isEqualTo("placed");
  }

  // ---- service callers with API keys ----

  @Test void warehouseServiceReadsAllOrdersWithItsKey() throws Exception {
    var res = send("GET", "/admin/orders", null, "X-API-Key", WAREHOUSE_KEY);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(body(res).get("data").size()).isEqualTo(2);
    assertThat(send("PATCH", "/orders/ord_1", "{\"status\":\"cancelled\"}", "X-API-Key", WAREHOUSE_KEY).statusCode())
        .isEqualTo(403);
  }

  @Test void methodNotAllowedListsEveryMethodOfTheResource() throws Exception {
    var res = as("alice", "DELETE", "/orders/ord_1", null);
    assertThat(res.statusCode()).isEqualTo(405);
    assertThat(res.headers().firstValue("Allow").orElseThrow()).contains("GET").contains("PATCH");
  }
}
