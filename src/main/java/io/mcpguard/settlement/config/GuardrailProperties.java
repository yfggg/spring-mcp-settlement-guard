package io.mcpguard.settlement.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "guardrail")
public record GuardrailProperties(
        Mandate mandate,
        Security security,
        Recovery recovery,
        Demo demo
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
}
