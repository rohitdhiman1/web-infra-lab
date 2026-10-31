package dev.webinfra.labs.orders;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.exc.StreamReadException;

/**
 * One place that maps failures to status codes. The body stays simple in A1:
 * {"error": "...", "fields": {...}}. A2 replaces it with RFC 9457 problem details.
 */
@RestControllerAdvice
public class ApiErrors {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> api(ApiException e) {
    return body(e.status, e.getMessage(), e.fields);
  }

  /** 400 if the bytes aren't JSON at all; 422 if it's JSON of the wrong shape (e.g. "quantity": "two"). */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
    if (e.getCause() instanceof StreamReadException || e.getCause() == null) {
      return body(HttpStatus.BAD_REQUEST, "request body is not valid JSON", Map.of());
    }
    return body(HttpStatus.UNPROCESSABLE_CONTENT, "request body has the wrong shape", Map.of());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e) {
    // Spring fills in Accept (and Accept-Patch for PATCH): tell the client what would work.
    String wanted = e.getSupportedMediaTypes().isEmpty() ? "application/json" : e.getSupportedMediaTypes().getFirst().toString();
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).headers(e.getHeaders())
        .body(Map.of("error", "send Content-Type: " + wanted));
  }

  static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Map<String, String> fields) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("error", message);
    if (!fields.isEmpty()) b.put("fields", fields);
    return ResponseEntity.status(status).body(b);
  }
}
