package io.mcpguard.settlement.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound port representing the downstream payment processor (e.g., Stripe, Adyen, or bank payout API).
 */
public interface PaymentGatewayPort {

    PaymentExecutionResult executeRefund(String orderId, BigDecimal amount, String currency, String idempotencyRef);

    record PaymentExecutionResult(
            String transactionId,
            String orderId,
            BigDecimal amount,
            String currency,
            Instant executedAt
    ) {
    }
}
