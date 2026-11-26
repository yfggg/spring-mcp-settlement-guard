package io.mcpguard.settlement.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "guardrail")
public record GuardrailProperties(
        Mandate mandate,
        Security security,
        Recovery recovery,
        Demo demo,
        Provider provider
) {
    public record Mandate(
            String currency,
            BigDecimal autoApproveSingleLimit,
            BigDecimal rolling24hOrderLimit,
            int approvalTtlHours
    ) {
    }

    /**
     * @param agentApiKey       shared secret for MCP agent callers
     * @param reviewerJwtSecret HS256 secret that signs finance reviewer tokens (at least 32 bytes)
     * @param reviewerJwtIssuer required {@code iss} claim on reviewer tokens
     * @param snapshotHmacKey   key for the HMAC over paused refund snapshots
     */
    public record Security(
            String agentApiKey,
            String reviewerJwtSecret,
            @DefaultValue("spring-mcp-settlement-guard") String reviewerJwtIssuer,
            @DefaultValue("mcp-demo-snapshot-hmac-2026") String snapshotHmacKey
    ) {
    }

    /**
     * Requests stuck in IN_PROGRESS / EXECUTING longer than {@code staleAfterMinutes} (for example after a crash)
     * are frozen as UNKNOWN_SUSPENDED / PAYOUT_UNKNOWN for manual reconciliation. Approval events left unpublished
     * for longer than {@code outboxGraceSeconds} are delivered again.
     */
    public record Recovery(
            int staleAfterMinutes,
            long sweepIntervalMs,
            @DefaultValue("30") int outboxGraceSeconds
    ) {
    }

    public record Demo(
            boolean seedEnabled,
            @DefaultValue("true") boolean httpEnabled
    ) {
    }

    /**
     * How frozen payouts may be released, and how often booked payouts are checked against the provider again.
     *
     * @param cancelSupported        the provider can void a refund that has not settled
     * @param settlementWindowHours  when cancel is unavailable, a missing refund may be released only after this long
     * @param reconcileLookbackDays  booked and released payouts newer than this are compared with the provider
     * @param reconcileIntervalMs    delay between reconciliation passes
     */
    public record Provider(
            @DefaultValue("true") boolean cancelSupported,
            @DefaultValue("24") int settlementWindowHours,
            @DefaultValue("7") int reconcileLookbackDays,
            @DefaultValue("3600000") long reconcileIntervalMs
    ) {
    }
}
