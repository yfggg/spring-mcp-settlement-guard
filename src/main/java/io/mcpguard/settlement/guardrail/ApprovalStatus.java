package io.mcpguard.settlement.guardrail;

/**
 * Lifecycle of a refund request.
 *
 * <pre>
 * PENDING_APPROVAL --approve (CAS)--> EXECUTING --gateway ok--------> EXECUTED
 *        |                               |
 *        |                               +--gateway error/timeout--> PAYOUT_UNKNOWN --confirm--> EXECUTED
 *        |                                                        \--release--> PAYOUT_RELEASED
 *        +--reject (CAS)--> REJECTED
 *        +--TTL elapsed---> EXPIRED
 * </pre>
 *
 * Auto-approved refunds enter directly at {@code EXECUTING}. {@code EXECUTING} and {@code PAYOUT_UNKNOWN}
 * keep counting against the order's balance and velocity window, and can never be approved again.
 * {@code PAYOUT_RELEASED} means finance confirmed the gateway did not move money, so the reservation is gone.
 */
public enum ApprovalStatus {
    PENDING_APPROVAL,
    EXECUTING,
    EXECUTED,
    PAYOUT_UNKNOWN,
    PAYOUT_RELEASED,
    REJECTED,
    EXPIRED
}
