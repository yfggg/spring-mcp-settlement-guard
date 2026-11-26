package io.mcpguard.settlement.domain;

/**
 * What the payment provider currently says about one idempotency reference.
 * {@code terminal} means the provider will not later take money for this same reference.
 */
public record ProviderRefundLookup(
        State state,
        boolean terminal,
        PaymentGatewayPort.PaymentExecutionResult refund
) {
    public enum State {
        SUCCEEDED,
        PENDING,
        FAILED,
        CANCELLED,
        NOT_FOUND
    }

    public static ProviderRefundLookup notFound() {
        return new ProviderRefundLookup(State.NOT_FOUND, false, null);
    }

    public static ProviderRefundLookup of(
            State state,
            boolean terminal,
            PaymentGatewayPort.PaymentExecutionResult refund) {
        return new ProviderRefundLookup(state, terminal, refund);
    }
}
