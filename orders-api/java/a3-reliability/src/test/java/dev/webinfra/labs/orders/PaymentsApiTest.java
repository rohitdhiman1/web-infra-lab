package dev.webinfra.labs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentsApiTest extends ApiTestBase {
  static final Duration T = Duration.ofSeconds(5);

  @Test void ordersAreStoredInPostgres() throws Exception {
    String order = createOrder();
    var res = get(order);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"total\":3000").contains("\"status\":\"pending\"");
  }

  @Test void paymentWithoutKeyIs400() throws Exception {
    var res = send(payment(createOrder(), null, 3000, T));
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("idempotency-key-missing");
  }

  @Test void paymentCreates201AndMarksOrderPaid() throws Exception {
    String order = createOrder();
    var res = send(payment(order, newKey(), 3000, T));
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.headers().firstValue("Location").orElseThrow()).startsWith(order + "/payments/pay_");
    assertThat(get(order).body()).contains("\"status\":\"paid\"");
  }

  @Test void sameKeyReplaysTheOriginalResponse() throws Exception {
    String order = createOrder();
    String key = newKey();
    var first = send(payment(order, key, 3000, T));
    var again = send(payment(order, key, 3000, T));
    assertThat(again.statusCode()).isEqualTo(201);
    assertThat(again.body()).isEqualTo(first.body());
    assertThat(again.headers().firstValue("Idempotent-Replayed")).hasValue("true");
    assertThat(gateway.charges()).isEqualTo(1);
    assertThat(get(order + "/payments").body()).containsOnlyOnce("pay_");
  }

  @Test void sameKeyDifferentRequestIs422() throws Exception {
    String order = createOrder();
    String key = newKey();
    send(payment(order, key, 3000, T));
    var res = send(payment(order, key, 2999, T));
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("idempotency-key-reused");
  }

  @Test void failedRequestFreesTheKey() throws Exception {
    String order = createOrder();
    String key = newKey();
    assertThat(send(payment(order, key, 1, T)).statusCode()).isEqualTo(422);   // wrong amount
    assertThat(send(payment(order, key, 3000, T)).statusCode()).isEqualTo(201); // same key, now valid
  }

  @Test void secondPaymentWithNewKeyIs409() throws Exception {
    String order = createOrder();
    send(payment(order, newKey(), 3000, T));
    var res = send(payment(order, newKey(), 3000, T));
    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(res.body()).contains("order-already-paid");
    assertThat(gateway.charges()).isEqualTo(1);
  }
}
