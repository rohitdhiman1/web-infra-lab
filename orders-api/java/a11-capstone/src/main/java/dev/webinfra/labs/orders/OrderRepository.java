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

  /** The owner comes from the caller's token, never from the request body. */
  public Order create(OrderInput in, String owner) {
    long id = db.sql("""
            INSERT INTO orders (customer, currency, items, total, status, owner)
            VALUES (?, ?, ?::jsonb, ?, 'pending', ?) RETURNING id""")
        .params(in.customer(), in.currency(), json.writeValueAsString(in.items()), total(in), owner)
        .query(Long.class).single();
    return find(id).orElseThrow();
  }

  private Order row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Order("ord_" + rs.getLong("id"), rs.getString("customer"), rs.getString("currency"),
        json.readValue(rs.getString("items"), new TypeReference<List<Order.OrderItem>>() {}),
        rs.getLong("total"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getInt("version"),
        rs.getString("owner"));
  }

  public Optional<Order> find(long id) {
    return db.sql("SELECT * FROM orders WHERE id = ?").param(id).query((rs, n) -> row(rs)).optional();
  }

  /**
   * A10, object-level authorisation in the query itself: an order that isn't yours comes back
   * exactly like one that doesn't exist. `anyOwner` is true for staff who may see every order.
   */
  public Optional<Order> findVisible(long id, String caller, boolean anyOwner) {
    return db.sql("SELECT * FROM orders WHERE id = ? AND (owner = ? OR ?)")
        .params(id, caller, anyOwner).query((rs, n) -> row(rs)).optional();
  }

  /** Only one request can move an order from pending to paying. Bumps the version: the ETag changes. */
  public boolean claimForPayment(long id) {
    return db.sql("UPDATE orders SET status = 'paying', version = version + 1 WHERE id = ? AND status = 'pending'")
        .param(id).update() == 1;
  }

  public void releaseClaim(long id) {
    db.sql("UPDATE orders SET status = 'pending', version = version + 1 WHERE id = ? AND status = 'paying'")
        .param(id).update();
  }

  /** One transaction: the order is marked paid if and only if the payment row exists. */
  @org.springframework.transaction.annotation.Transactional
  public Payment recordPayment(long orderId, long amount, String currency, String chargeId) {
    db.sql("UPDATE orders SET status = 'paid', version = version + 1 WHERE id = ?").param(orderId).update();
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

  /**
   * Keyset (cursor) pagination: "the next `limit` orders older than the last one you saw".
   * The WHERE id < ? uses the primary-key index, so page 1,000 costs the same as page 1,
   * and rows inserted or deleted elsewhere can't shift what you see next.
   */
  public List<Order> pageAfter(String owner, Long beforeId, String status, int limit) {
    return db.sql("""
            SELECT * FROM orders
            WHERE (CAST(? AS TEXT) IS NULL OR owner = ?)
              AND (CAST(? AS BIGINT) IS NULL OR id < ?) AND (CAST(? AS TEXT) IS NULL OR status = ?)
            ORDER BY id DESC LIMIT ?""")
        .params(owner, owner, beforeId, beforeId, status, status, limit)
        .query((rs, n) -> row(rs)).list();
  }

  /** Offset pagination, for comparison: the database still reads and throws away `offset` rows. */
  public List<Order> pageAtOffset(String owner, int offset, String status, int limit) {
    return db.sql("""
            SELECT * FROM orders
            WHERE (CAST(? AS TEXT) IS NULL OR owner = ?) AND (CAST(? AS TEXT) IS NULL OR status = ?)
            ORDER BY id DESC LIMIT ? OFFSET ?""")
        .params(owner, owner, status, status, limit, offset)
        .query((rs, n) -> row(rs)).list();
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
