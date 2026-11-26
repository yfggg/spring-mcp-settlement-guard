package io.mcpguard.settlement.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound port representing the downstream payment processor (e.g., Stripe, Adyen, or bank payout API).
 */
public interface PaymentGatewayPort {

    PaymentExecutionResult executeRefund(String orderId, BigDecimal amount, String currency, String idempotencyRef);

    /**
     * Looks up a refund previously sent with this idempotency reference.
     * {@link ProviderRefundLookup.State#NOT_FOUND} means this lookup saw nothing; it does not promise the provider
     * will never pay later. Throws when the provider cannot be reached, so callers never mistake an outage for
     * "not found".
     */
    ProviderRefundLookup lookupRefund(String orderId, String idempotencyRef);

    /**
     * Asks the provider to void an in-flight or not-yet-accepted refund for this idempotency reference.
     * A successful call must make a later {@link #lookupRefund} return {@code CANCELLED} or a terminal {@code FAILED}.
     */
    void cancelRefund(String orderId, String idempotencyRef);

    /** When false, {@link #cancelRefund} throws {@link CancelNotSupportedException}. */
    boolean cancelSupported();

    class CancelNotSupportedException extends RuntimeException {
        public CancelNotSupportedException(String message) {
            super(message);
        }
    }

    record PaymentExecutionResult(
            String transactionId,
            String orderId,
            BigDecimal amount,
            String currency,
            Instant executedAt
    ) {
    }
}
