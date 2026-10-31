package dev.webinfra.labs.orders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class OrderStore {
  private final Map<String, Order> orders = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong(1);

  public Order create(OrderInput in) {
    String id = "ord_" + nextId.getAndIncrement();
    Order order = Order.from(id, in, "pending", Instant.now());
    orders.put(id, order);
    return order;
  }

  public Optional<Order> find(String id) {
    return Optional.ofNullable(orders.get(id));
  }

  public void save(Order order) {
    orders.put(order.id(), order);
  }

  public boolean delete(String id) {
    return orders.remove(id) != null;
  }

  public List<Order> all() {
    List<Order> list = new ArrayList<>(orders.values());
    list.sort((a, b) -> Long.compare(num(a.id()), num(b.id())));
    return list;
  }

  private static long num(String id) {
    return Long.parseLong(id.substring("ord_".length()));
  }
}
