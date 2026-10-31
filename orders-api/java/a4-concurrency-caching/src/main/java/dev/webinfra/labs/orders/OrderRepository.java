package dev.webinfra.labs.orders;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class OrderRepository {
  private final JdbcClient db;
  private final ObjectMapper json;

  public OrderRepository(JdbcClient db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  static long total(OrderInput in) {
    return in.items().stream().mapToLong(i -> i.quantity() * i.unitPrice()).sum();
  }

  public Order create(OrderInput in) {
    long id = db.sql("""
            INSERT INTO orders (customer, currency, items, total, status)
            VALUES (?, ?, ?::jsonb, ?, 'pending') RETURNING id""")
        .params(in.customer(), in.currency(), json.writeValueAsString(in.items()), total(in))
        .query(Long.class).single();
    return find(id).orElseThrow();
  }

  public Optional<Order> find(long id) {
    return db.sql("SELECT * FROM orders WHERE id = ?").param(id)
        .query((rs, n) -> new Order("ord_" + rs.getLong("id"), rs.getString("customer"), rs.getString("currency"),
            json.readValue(rs.getString("items"), new TypeReference<List<Order.OrderItem>>() {}),
            rs.getLong("total"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getInt("version")))
        .optional();
  }

  /**
   * Optimistic locking: write only if the version is still the one the client read.
   * Check and write happen in one statement, so two writers can't both succeed.
   * Returns false if someone else changed the order first.
   */
  public boolean updateIfVersion(long id, int expectedVersion, OrderInput in) {
    return db.sql("""
            UPDATE orders SET customer = ?, currency = ?, items = ?::jsonb, total = ?, status = ?,
                              version = version + 1
            WHERE id = ? AND version = ?""")
        .params(in.customer(), in.currency(), json.writeValueAsString(in.items()), total(in), in.status(),
            id, expectedVersion)
        .update() == 1;
  }
}
