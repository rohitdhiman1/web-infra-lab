package dev.webinfra.labs.orders;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
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

  public Order create(OrderInput in) {
    long total = in.items().stream().mapToLong(i -> i.quantity() * i.unitPrice()).sum();
    long id = db.sql("""
            INSERT INTO orders (customer, currency, items, total, status)
            VALUES (?, ?, ?::jsonb, ?, 'pending') RETURNING id""")
        .params(in.customer(), in.currency(), json.writeValueAsString(in.items()), total)
        .query(Long.class).single();
    return find(id).orElseThrow();
  }

  public Optional<Order> find(long id) {
    return db.sql("SELECT * FROM orders WHERE id = ?").param(id)
        .query((rs, n) -> new Order("ord_" + rs.getLong("id"), rs.getString("customer"), rs.getString("currency"),
            json.readValue(rs.getString("items"), new TypeReference<List<Order.OrderItem>>() {}),
            rs.getLong("total"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()))
        .optional();
  }

  /**
   * Atomically claim an order for payment: only one request can move it from pending to paying.
   * The WHERE clause does the check and the update in one step, so two concurrent payments
   * can't both pass a "not paid yet" check.
   */
  public boolean claimForPayment(long id) {
    return db.sql("UPDATE orders SET status = 'paying' WHERE id = ? AND status = 'pending'").param(id).update() == 1;
  }

  public void releaseClaim(long id) {
    db.sql("UPDATE orders SET status = 'pending' WHERE id = ? AND status = 'paying'").param(id).update();
  }

  /** One transaction: the order is marked paid if and only if the payment row exists. */
  @Transactional
  public Payment recordPayment(long orderId, long amount, String currency, String chargeId) {
    db.sql("UPDATE orders SET status = 'paid' WHERE id = ?").param(orderId).update();
    return db.sql("""
            INSERT INTO payments (order_id, amount, currency, charge_id) VALUES (?, ?, ?, ?)
            RETURNING id, created_at""")
        .params(orderId, amount, currency, chargeId)
        .query((rs, n) -> new Payment("pay_" + rs.getLong("id"), "ord_" + orderId, amount, currency, chargeId,
            rs.getTimestamp("created_at").toInstant()))
        .single();
  }

  public List<Payment> payments(long orderId) {
    return db.sql("SELECT * FROM payments WHERE order_id = ? ORDER BY id").param(orderId)
        .query((rs, n) -> new Payment("pay_" + rs.getLong("id"), "ord_" + orderId, rs.getLong("amount"),
            rs.getString("currency"), rs.getString("charge_id"), rs.getTimestamp("created_at").toInstant()))
        .list();
  }
}
