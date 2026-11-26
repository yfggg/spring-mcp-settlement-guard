package io.mcpguard.settlement.guardrail;

public final class GuardrailExceptions {

    private GuardrailExceptions() {
    }

    public static class InvalidSettlementRequestException extends RuntimeException {
        public InvalidSettlementRequestException(String message) {
            super(message);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    /**
     * The same idempotency key is already claimed and has not finished.
     * Callers must retry that key. A new key is a different refund.
     */
    public static class IdempotencyInProgressException extends RuntimeException {
        public IdempotencyInProgressException(String message) {
            super(message);
        }
    }

    public static class ApprovalNotFoundException extends RuntimeException {
        public ApprovalNotFoundException(String message) {
            super(message);
        }
    }

    public static class ApprovalStateConflictException extends RuntimeException {
        public ApprovalStateConflictException(String message) {
            super(message);
        }
    }

    public static class TamperedSnapshotException extends RuntimeException {
        public TamperedSnapshotException(String message) {
            super(message);
        }
    }

    /**
     * The payment gateway failed or timed out after a payout was dispatched, so money may or may not have moved.
     * The request is frozen for manual reconciliation and must never be retried automatically.
     */
    public static class PayoutOutcomeUnknownException extends RuntimeException {
        public PayoutOutcomeUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
