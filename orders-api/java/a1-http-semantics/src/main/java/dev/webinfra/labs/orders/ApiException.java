package dev.webinfra.labs.orders;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Thrown by handlers; ApiErrors turns it into a status code and a small JSON body. */
public class ApiException extends RuntimeException {
  final HttpStatus status;
  final Map<String, String> fields;

  ApiException(HttpStatus status, String message) {
    this(status, message, Map.of());
  }

  ApiException(HttpStatus status, String message, Map<String, String> fields) {
    super(message);
    this.status = status;
    this.fields = fields;
  }
}
