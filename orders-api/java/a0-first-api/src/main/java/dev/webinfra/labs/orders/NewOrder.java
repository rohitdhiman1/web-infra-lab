package dev.webinfra.labs.orders;

import java.util.List;

/** What a client sends to create an order. The server decides id, total, status and createdAt. */
public record NewOrder(String customer, String currency, List<Order.OrderItem> items) {}
