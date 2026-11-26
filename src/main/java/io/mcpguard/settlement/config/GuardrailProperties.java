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

    public record Security(
            String agentApiKey,
            String reviewerApiKey,
            @DefaultValue("mcp-demo-snapshot-hmac-2026") String snapshotHmacKey
    ) {
    }

    /**
     * Requests stuck in IN_PROGRESS / EXECUTING longer than {@code staleAfterMinutes} (for example after a crash)
     * are frozen as UNKNOWN_SUSPENDED / PAYOUT_UNKNOWN for manual reconciliation.
     */
    public record Recovery(
            int staleAfterMinutes,
            long sweepIntervalMs
    ) {
    }

    public record Demo(
            boolean seedEnabled,
            @DefaultValue("true") boolean httpEnabled
    ) {
    }
}
