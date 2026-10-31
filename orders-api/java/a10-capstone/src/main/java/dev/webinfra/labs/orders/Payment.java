package dev.webinfra.labs.orders;

import java.time.Instant;

public record Payment(String id, String orderId, long amount, String currency, String chargeId, Instant createdAt) {}
