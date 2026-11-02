package dev.webinfra.labs.orders;

import jakarta.servlet.http.HttpServletResponse;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Authentication only. Spring Security answers "who is this?" and rejects bad credentials with 401.
 * "May they do this?" is decided in the handlers (Caller.require, OrderService), where the order is known.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http, AuthenticationEntryPoint entryPoint) throws Exception {
    return http
        // No cookies and no sessions: every request carries its own credential, so CSRF doesn't apply.
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/token", "/.well-known/jwks.json", "/error").permitAll()
            .anyRequest().authenticated())   // deny by default: a new endpoint is protected until opened up
        .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(entryPoint))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .addFilterBefore(new ApiKeyFilter(entryPoint), BearerTokenAuthenticationFilter.class)
        .build();
  }

  /**
   * Verifies every bearer token: ES256 signature with our public key (and only ES256, so an
   * "alg": "none" or HS256 token is refused), then exp/nbf, issuer and audience.
   */
  @Bean
  JwtDecoder jwtDecoder(SigningKeys keys, @Value("${auth.issuer}") String issuer,
                        @Value("${auth.audience}") String audience) {
    // In production: NimbusJwtDecoder.withIssuerLocation(issuer), which fetches and caches the provider's JWKS.
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(keys.publicJwks()))
        .jwsAlgorithm(SignatureAlgorithm.ES256).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        new JwtTimestampValidator(Duration.ofSeconds(30)),   // allow 30 s of clock skew between servers
        new JwtIssuerValidator(issuer),
        new JwtClaimValidator<java.util.List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
    return decoder;
  }

  /** Every 401: a problem body, plus WWW-Authenticate saying how to authenticate. */
  @Bean
  AuthenticationEntryPoint entryPoint(ObjectMapper json) {
    return (req, res, e) -> {
      boolean apiKey = req.getHeader("X-API-Key") != null;
      boolean token = e instanceof OAuth2AuthenticationException;
      String type = apiKey || token ? "invalid-token" : "unauthenticated";
      String challenge = apiKey ? "ApiKey realm=\"orders-api\""
          : token ? "Bearer realm=\"orders-api\", error=\"invalid_token\""
          : "Bearer realm=\"orders-api\"";   // no credential at all: no error code, just "how"
      String detail = apiKey ? "unknown API key"
          : token ? "the access token is invalid or expired"
          : "send Authorization: Bearer <token>, or X-API-Key";
      ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
      p.setType(URI.create(ApiErrors.TYPES + type));
      p.setTitle(ApiErrors.TITLES.get(type));
      p.setInstance(URI.create(req.getRequestURI()));
      p.setProperty("requestId", req.getAttribute(RequestIdFilter.ATTRIBUTE));
      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      res.setHeader("WWW-Authenticate", challenge);
      res.setContentType("application/problem+json");
      res.setCharacterEncoding("UTF-8");
      res.getWriter().write(json.writeValueAsString(p));
    };
  }

}
