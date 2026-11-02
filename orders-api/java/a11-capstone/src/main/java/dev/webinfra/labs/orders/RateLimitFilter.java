package dev.webinfra.labs.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * One token bucket per client, keyed by its credential (API key or bearer token), or by IP if it has none.
 * This runs before authentication, so it also slows down anyone guessing credentials. A credential it
 * hasn't verified yet is only a bucket name here; production setups usually add a per-IP limit too.
 * Every response says how much budget is left; a refused request gets 429 + Retry-After.
 *
 * In-memory, so each server instance has its own buckets: with N instances behind a load balancer
 * a client really gets N times the limit. Shared limits need a shared store (Redis, or the gateway).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
  private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
  private final int capacity;
  private final double rate;
  private final ObjectMapper json;

  public RateLimitFilter(@Value("${ratelimit.capacity}") int capacity,
                         @Value("${ratelimit.refill-per-second}") double rate, ObjectMapper json) {
    this.capacity = capacity;
    this.rate = rate;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return "OPTIONS".equals(req.getMethod());   // CORS preflights are free: browsers send them, not users
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String apiKey = req.getHeader("X-API-Key");
    String bearer = req.getHeader("Authorization");
    // Behind a proxy, getRemoteAddr() is the proxy. Use X-Forwarded-For only from a proxy you trust (Part 8).
    String client = apiKey != null ? "key:" + ApiKeyFilter.sha256(apiKey)
        : bearer != null ? "token:" + ApiKeyFilter.sha256(bearer)   // hashed: never keep credentials in memory maps
        : "ip:" + req.getRemoteAddr();
    var d = buckets.computeIfAbsent(client, k -> new TokenBucket(capacity, rate, System.nanoTime()))
        .take(System.nanoTime());
    res.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
    res.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
    if (d.allowed()) {
      chain.doFilter(req, res);
      return;
    }
    res.setStatus(429);
    res.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
    res.setContentType("application/problem+json");
    res.getWriter().write(json.writeValueAsString(Map.of(
        "type", ApiErrors.TYPES + "rate-limited",
        "title", "Too many requests",
        "status", 429,
        "detail", "rate limit of " + capacity + " requests, refilled at " + rate + " per second; retry after "
            + d.retryAfterSeconds() + " s",
        "instance", req.getRequestURI(),
        "requestId", String.valueOf(req.getAttribute(RequestIdFilter.ATTRIBUTE)))));
  }
}
