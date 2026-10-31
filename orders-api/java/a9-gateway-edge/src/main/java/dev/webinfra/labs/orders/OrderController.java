package dev.webinfra.labs.orders;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A9 is about what sits around the endpoints, so the endpoints themselves are minimal. */
@RestController
public class OrderController {
  @GetMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, List<Object>> list() {
    return Map.of("data", List.of());
  }

  @GetMapping(path = "/orders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> get(@PathVariable String id) {
    throw new ApiException(HttpStatus.NOT_FOUND, "order-not-found", "order " + id + " not found");
  }
}
