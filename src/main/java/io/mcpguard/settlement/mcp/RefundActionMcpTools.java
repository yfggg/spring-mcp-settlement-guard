package io.mcpguard.settlement.mcp;

import io.mcpguard.settlement.guardrail.ApprovalGateService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * State-mutating MCP refund tools protected by local hidden mandates, rolling 24h anti-smurfing locks,
 * mandatory idempotency keys, and two-phase human-in-the-loop sign-off.
 *
 * <p>Arguments are validated by Bean Validation before the method body runs, so malformed agent input
 * is rejected before an idempotency slot is claimed or an order row lock is taken.
 */
@Service
@Validated
public class RefundActionMcpTools {

    private final ApprovalGateService approvalGateService;

    public RefundActionMcpTools(ApprovalGateService approvalGateService) {
        this.approvalGateService = approvalGateService;
    }

    @Tool(
            name = "issue_customer_refund",
            description = "Submit a structured customer refund governed by deterministic settlement guardrails and mandatory idempotency protection."
    )
    public ApprovalGateService.RefundDecision issueCustomerRefund(
            @ToolParam(description = "Target order identifier (letters, digits, '-' or '_'), e.g. ORD-2026-8841")
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$", message = "must contain only letters, digits, '-' or '_'")
            String orderId,

            @ToolParam(description = "Exact decimal refund amount as a string with at most 2 fraction digits, e.g. 180.00")
            @NotBlank
            @Digits(integer = 10, fraction = 2)
            @DecimalMin(value = "0.01")
            String amount,

            @ToolParam(description = "ISO-4217 three-letter currency code, e.g. USD")
            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter ISO-4217 code")
            String currency,

            @ToolParam(description = "Structured reason code (letters, digits or '_'), e.g. DUPLICATE_CHARGE")
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "must contain only letters, digits or '_'")
            String reasonCode,

            @ToolParam(
                    description = "Optional idempotency key (8-128 chars). Omit it and the server uses a SHA-256 of order, amount, currency, and reason, so the same request pays once. Pass your own key only when a second identical refund is intentional; then reuse that exact key on every retry.",
                    required = false)
            @Size(max = 128)
            @Pattern(regexp = "^$|^[A-Za-z0-9._:-]{8,128}$", message = "must contain only letters, digits, '.', '_', ':' or '-'")
            String idempotencyKey) {

        return approvalGateService.submitRefund(new ApprovalGateService.RefundCommand(
                orderId,
                new BigDecimal(amount),
                currency,
                reasonCode,
                idempotencyKey
        ));
    }

    @Tool(
            name = "get_approval_status",
            description = "Query the current approval status and settled transaction ID for a previously paused refund approval ID."
    )
    public AgentApprovalStatusView getApprovalStatus(
            @ToolParam(description = "Approval identifier returned when a refund was paused, e.g. apr_9f82c1d04ab3")
            @NotBlank
            @Pattern(regexp = "^apr_[a-f0-9]{12}$", message = "must be an approval id returned by issue_customer_refund")
            String approvalId) {
        ApprovalGateService.ApprovalStatusView view = approvalGateService.getApprovalStatus(approvalId);
        return new AgentApprovalStatusView(
                view.approvalId(),
                view.orderId(),
                view.amount(),
                view.currency(),
                view.status(),
                view.refundTxId(),
                view.expiresAt()
        );
    }

    /** Agent-facing status projection. Reviewer identity stays internal to the finance boundary. */
    public record AgentApprovalStatusView(
            String approvalId,
            String orderId,
            BigDecimal amount,
            String currency,
            String status,
            String refundTxId,
            Instant expiresAt
    ) {
    }
}
