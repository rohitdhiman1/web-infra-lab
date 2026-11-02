package dev.webinfra.labs.orders;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.exc.StreamReadException;

/**
 * A2: every error is an RFC 9457 problem details document, sent as application/problem+json.
 *
 * Extending ResponseEntityExceptionHandler makes Spring's own errors (404 for unknown paths,
 * 405 + Allow, 406, 415 + Accept-Patch) use the same format, headers included.
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
  static final String TYPES = "https://api.example.com/problems/";

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> api(ApiException e, WebRequest request) {
    ProblemDetail p = problem(e.status, e.type, e.getMessage(), request);
    if (!e.fields.isEmpty()) {
      // Extension member: one entry per invalid field, located with a JSON Pointer.
      p.setProperty("errors", e.fields.entrySet().stream()
          .map(f -> Map.of("pointer", f.getKey(), "detail", f.getValue())).toList());
    }
    var response = ResponseEntity.status(e.status);
    e.headers.forEach((name, value) -> response.header(name, value));
    return response.body(p);
  }

  /** 400 if the bytes aren't JSON at all; 422 if it's JSON of the wrong shape. */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    boolean syntax = e.getCause() instanceof StreamReadException || e.getCause() == null;
    ProblemDetail p = syntax
        ? problem(HttpStatus.BAD_REQUEST, "malformed-body", "request body is not valid JSON", request)
        : problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation-error", "request body has the wrong shape", request);
    return ResponseEntity.status(p.getStatus()).body(p);
  }

  /** Spring's own errors arrive here already as ProblemDetail; just fill in instance. */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail p) {
      if (p.getInstance() == null) p.setInstance(URI.create(path(request)));
      p.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE, WebRequest.SCOPE_REQUEST));
    }
    return super.createResponseEntity(body, headers, status, request);
  }

  static ProblemDetail problem(HttpStatus status, String type, String detail, WebRequest request) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
    p.setType(URI.create(TYPES + type));
    p.setTitle(TITLES.getOrDefault(type, status.getReasonPhrase()));
    p.setInstance(URI.create(path(request)));
    p.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE, WebRequest.SCOPE_REQUEST));
    return p;
  }

  static final Map<String, String> TITLES = Map.ofEntries(
      Map.entry("validation-error", "Your request is not valid"),
      Map.entry("malformed-body", "Request body is not valid JSON"),
      Map.entry("order-not-found", "Order not found"),
      Map.entry("precondition-required", "If-Match is required"),
      Map.entry("precondition-failed", "The order has changed since you read it"),
      Map.entry("invalid-parameter", "A query parameter is not valid"),
      Map.entry("order-not-payable", "Order can't be paid"),
      Map.entry("order-locked", "Order can't be changed"),
      Map.entry("idempotency-key-missing", "Idempotency-Key header is required"),
      Map.entry("idempotency-key-reused", "Idempotency-Key was used for a different request"),
      Map.entry("idempotency-key-in-progress", "A request with this Idempotency-Key is still in progress"),
      Map.entry("payment-failed", "The payment gateway failed"),
      Map.entry("rate-limited", "Too many requests"),
      Map.entry("unauthenticated", "Authentication required"),
      Map.entry("invalid-token", "Invalid or expired credential"),
      Map.entry("insufficient-scope", "Not allowed"),
      Map.entry("unknown-user", "Unknown user"));

  private static String path(WebRequest request) {
    return request instanceof ServletWebRequest s ? s.getRequest().getRequestURI() : "";
  }

}
