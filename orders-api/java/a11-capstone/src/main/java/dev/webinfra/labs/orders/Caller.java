package dev.webinfra.labs.orders;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling, whichever way they authenticated: a user with a JWT, or a service with an API key.
 * Handlers see only this, never the raw credential.
 */
public record Caller(String id, String role, Set<String> scopes) {
  static Caller from(Authentication auth) {
    if (auth instanceof JwtAuthenticationToken jwt) {
      var t = jwt.getToken();
      String scope = t.getClaimAsString("scope");
      return new Caller(t.getSubject(), t.getClaimAsString("role"),
          scope == null ? Set.of() : Set.of(scope.split(" ")));
    }
    if (auth instanceof ApiKeyFilter.ServiceAuthentication s) {
      return new Caller(s.client().name(), "service", s.client().scopes());
    }
    throw new IllegalStateException("unauthenticated request reached a handler");
  }

  boolean has(String scope) {
    return scopes.contains(scope);
  }

  /**
   * Function-level authorisation: may this caller do this kind of thing at all?
   * 403, and the header tells an OAuth client which scope it would need.
   */
  void require(String scope) {
    if (!has(scope)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "insufficient-scope", "this needs the " + scope + " scope",
          java.util.Map.of(), java.util.Map.of("WWW-Authenticate",
              "Bearer error=\"insufficient_scope\", scope=\"" + scope + "\""));
    }
  }
}
