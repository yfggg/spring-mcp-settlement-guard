package io.mcpguard.settlement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port representing the downstream payment processor (e.g., Stripe, Adyen, or bank payout API).
 */
public interface PaymentGatewayPort {

    PaymentExecutionResult executeRefund(String orderId, BigDecimal amount, String currency, String idempotencyRef);

    /**
     * Looks up a refund previously sent with this idempotency reference. Empty means the provider never accepted
     * one. Throws when the provider cannot be reached, so callers never mistake an outage for "no refund".
     * A real adapter maps this to the provider's own lookup, for example a refund search by idempotency metadata.
     */
    Optional<PaymentExecutionResult> lookupRefund(String orderId, String idempotencyRef);

    record PaymentExecutionResult(
            String transactionId,
            String orderId,
            BigDecimal amount,
            String currency,
            Instant executedAt
    ) {
    }
}
