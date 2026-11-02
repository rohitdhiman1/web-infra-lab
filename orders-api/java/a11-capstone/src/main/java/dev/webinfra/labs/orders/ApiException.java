package dev.webinfra.labs.orders;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Thrown by handlers; ApiErrors turns it into an RFC 9457 problem details response. */
public class ApiException extends RuntimeException {
  final HttpStatus status;
  final String type;
  final Map<String, String> fields;
  final Map<String, String> headers;

  ApiException(HttpStatus status, String type, String message) {
    this(status, type, message, Map.of(), Map.of());
  }

  ApiException(HttpStatus status, String type, String message, Map<String, String> fields) {
    this(status, type, message, fields, Map.of());
  }

  /** @param type the last part of the problem type URI, e.g. "order-not-found" */
  ApiException(HttpStatus status, String type, String message, Map<String, String> fields, Map<String, String> headers) {
    super(message);
    this.status = status;
    this.type = type;
    this.fields = fields;
    this.headers = headers;
  }
}
