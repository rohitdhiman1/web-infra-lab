package dev.webinfra.labs.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every request gets an id: the caller's X-Request-Id if it looks sane, otherwise a new UUID.
 * It goes back in the response header, into every problem body, and into every log line (MDC),
 * so one id ties a user's error report to the exact log lines across services.
 * (W3C Trace Context's traceparent header does the same job for distributed tracing.)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Request-Id";
  public static final String ATTRIBUTE = "requestId";
  // Never trust input blindly: a header like this ends up in logs, so restrict it.
  static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String incoming = req.getHeader(HEADER);
    String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    req.setAttribute(ATTRIBUTE, id);
    res.setHeader(HEADER, id);
    MDC.put(ATTRIBUTE, id);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove(ATTRIBUTE);
    }
  }
}
