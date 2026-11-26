package io.mcpguard.settlement.guardrail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local quick-start stand-in for an MCP client. Disabled by {@code guardrail.demo.http-enabled=false}.
 */
@RestController
@ConditionalOnProperty(prefix = "guardrail.demo", name = "http-enabled", havingValue = "true", matchIfMissing = true)
public class DemoRefundController {

    private final ApprovalGateService approvalGateService;

    public DemoRefundController(ApprovalGateService approvalGateService) {
        this.approvalGateService = approvalGateService;
    }

    @PostMapping("/api/demo/simulate-agent-refund")
    public ResponseEntity<ApprovalGateService.RefundDecision> simulateAgentRefund(
            @Valid @RequestBody SimulateRefundHttpRequest request) {
        ApprovalGateService.RefundDecision decision = approvalGateService.submitRefund(
                new ApprovalGateService.RefundCommand(
                        request.orderId(),
                        request.amount(),
                        request.currency(),
                        request.reasonCode(),
                        request.idempotencyKey()
                )
        );
        return ResponseEntity.status(httpStatusFor(decision.status())).body(decision);
    }

    /**
     * The HTTP status reflects whether money has moved, so a client that reads only the status code
     * never mistakes a pending or uncertain refund for a completed one.
     */
    static HttpStatus httpStatusFor(String decisionStatus) {
        return switch (ApprovalStatus.valueOf(decisionStatus)) {
            case EXECUTED -> HttpStatus.OK;
            case PENDING_APPROVAL, EXECUTING -> HttpStatus.ACCEPTED;
            case PAYOUT_UNKNOWN -> HttpStatus.BAD_GATEWAY;
            case REJECTED, EXPIRED, PAYOUT_RELEASED -> HttpStatus.CONFLICT;
        };
    }

    public record SimulateRefundHttpRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$") String orderId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_]+$") String reasonCode,
            @Size(max = 128) @Pattern(regexp = "^$|^[A-Za-z0-9._:-]{8,128}$") String idempotencyKey
    ) {
    }
}
