package dev.webinfra.labs.orders;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Thrown by handlers; ApiErrors turns it into a status code and a small JSON body. */
public class ApiException extends RuntimeException {
  final HttpStatus status;
  final String type;
  final Map<String, String> fields;

  ApiException(HttpStatus status, String type, String message) {
    this(status, type, message, Map.of());
  }

  /** @param type the last part of the problem type URI, e.g. "order-not-found" */
  ApiException(HttpStatus status, String type, String message, Map<String, String> fields) {
    super(message);
    this.status = status;
    this.type = type;
    this.fields = fields;
  }
}
