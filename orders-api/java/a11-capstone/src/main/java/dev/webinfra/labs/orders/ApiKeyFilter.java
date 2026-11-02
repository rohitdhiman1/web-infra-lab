package dev.webinfra.labs.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service callers send X-API-Key. The server keeps only a SHA-256 hash of each key,
 * so a leaked database doesn't leak working keys. (A fast hash is fine here: API keys are long
 * and random, unlike passwords, which need a slow hash such as Argon2 or bcrypt.)
 *
 * Not a @Component on purpose: Spring Boot would also register it as a plain servlet filter.
 */
public class ApiKeyFilter extends OncePerRequestFilter {
  public record ServiceClient(String name, Set<String> scopes) {}

  /** What a successful API key check puts in the security context. */
  public static final class ServiceAuthentication extends AbstractAuthenticationToken {
    private final ServiceClient client;

    ServiceAuthentication(ServiceClient client) {
      super(client.scopes().stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
      this.client = client;
      setAuthenticated(true);
    }

    public ServiceClient client() {
      return client;
    }

    @Override public Object getCredentials() { return null; }
    @Override public Object getPrincipal() { return client.name(); }
  }

  /**
   * Hash of key -> client. The plaintext key for the warehouse service is
   * "wh_test_4f9c2a7d1e8b6035" (a throwaway lab value; real keys come from a secret manager).
   */
  static final Map<String, ServiceClient> KEYS = Map.of(
      sha256("wh_test_4f9c2a7d1e8b6035"),
      new ServiceClient("warehouse", Set.of("orders:read", "orders:read:any")));

  private final AuthenticationEntryPoint entryPoint;

  ApiKeyFilter(AuthenticationEntryPoint entryPoint) {
    this.entryPoint = entryPoint;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String key = req.getHeader("X-API-Key");
    if (key == null) {   // not an API key caller: let the bearer token filter have a go
      chain.doFilter(req, res);
      return;
    }
    ServiceClient client = KEYS.get(sha256(key));
    if (client == null) {
      SecurityContextHolder.clearContext();
      entryPoint.commence(req, res, new BadCredentialsException("unknown API key"));
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new ServiceAuthentication(client));
    SecurityContextHolder.setContext(context);
    chain.doFilter(req, res);
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
