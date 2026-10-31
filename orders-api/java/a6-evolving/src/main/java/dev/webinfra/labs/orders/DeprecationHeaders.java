package dev.webinfra.labs.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every v1 response says, in standard headers, that v1 is going away:
 *   Deprecation (RFC 9745): since when it's deprecated, as "@" + Unix seconds
 *   Sunset (RFC 8594):      when it will stop working, as an HTTP date
 *   Link rel="deprecation": where to read about it; rel="successor-version": what to use instead
 */
@Component
public class DeprecationHeaders extends OncePerRequestFilter {
  static final String DEPRECATED_SINCE = "@1793491200";             // 2026-11-01T00:00:00Z
  static final String SUNSET = "Fri, 30 Apr 2027 00:00:00 GMT";

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/v1/")) {
      res.setHeader("Deprecation", DEPRECATED_SINCE);
      res.setHeader("Sunset", SUNSET);
      res.addHeader("Link", "<https://api.example.com/docs/migrating-to-v2>; rel=\"deprecation\"");
      res.addHeader("Link", "</v2/orders>; rel=\"successor-version\"");
    }
    chain.doFilter(req, res);
  }
}
