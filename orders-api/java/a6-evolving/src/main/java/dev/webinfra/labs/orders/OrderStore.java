package dev.webinfra.labs.orders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** In-memory: this module is about the contract, not storage. */
@Component
public class OrderStore {
  private final Map<String, StoredOrder> orders = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong(1);

  public StoredOrder create(String email, String name, String currency, List<StoredOrder.Item> items, String note) {
    long total = items.stream().mapToLong(i -> i.quantity() * i.unitPrice()).sum();
    var o = new StoredOrder("ord_" + ids.getAndIncrement(), email, name, currency, List.copyOf(items), note, total,
        "pending", Instant.now());
    orders.put(o.id(), o);
    return o;
  }

  public Optional<StoredOrder> find(String id) {
    return Optional.ofNullable(orders.get(id));
  }
}
