package io.mcpguard.settlement.guardrail;

import io.mcpguard.settlement.config.McpSecurityConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human-in-the-loop approval endpoints.
 * Approve and reject accept no amount. Confirm and release settle a frozen payout without calling the gateway again.
 * Execution uses the HMAC snapshot stored when the refund was paused.
 */
@RestController
public class ApprovalRestController {

    private final ApprovalGateService approvalGateService;

    public ApprovalRestController(ApprovalGateService approvalGateService) {
        this.approvalGateService = approvalGateService;
    }

    @PostMapping("/api/approvals/{approvalId}/approve")
    public ResponseEntity<ApprovalGateService.RefundDecision> approve(
            @PathVariable String approvalId,
            @RequestHeader(McpSecurityConfig.HEADER_REVIEWER_ID) String reviewerId) {
        return ResponseEntity.ok(approvalGateService.approve(approvalId, reviewerId));
    }

    @PostMapping("/api/approvals/{approvalId}/reject")
    public ResponseEntity<ApprovalGateService.RefundDecision> reject(
            @PathVariable String approvalId,
            @RequestHeader(McpSecurityConfig.HEADER_REVIEWER_ID) String reviewerId) {
        return ResponseEntity.ok(approvalGateService.reject(approvalId, reviewerId));
    }

    @GetMapping("/api/approvals/{approvalId}")
    public ResponseEntity<ApprovalGateService.ApprovalStatusView> getStatus(@PathVariable String approvalId) {
        return ResponseEntity.ok(approvalGateService.getApprovalStatus(approvalId));
    }

    @PostMapping("/api/approvals/{approvalId}/confirm-payout")
    public ResponseEntity<ApprovalGateService.RefundDecision> confirmPayout(
            @PathVariable String approvalId,
            @RequestHeader(McpSecurityConfig.HEADER_REVIEWER_ID) String reviewerId,
            @Valid @RequestBody ConfirmPayoutRequest request) {
        return ResponseEntity.ok(approvalGateService.confirmSettledPayout(
                approvalId, reviewerId, request.transactionId()));
    }

    @PostMapping("/api/approvals/{approvalId}/release-payout")
    public ResponseEntity<ApprovalGateService.RefundDecision> releasePayout(
            @PathVariable String approvalId,
            @RequestHeader(McpSecurityConfig.HEADER_REVIEWER_ID) String reviewerId) {
        return ResponseEntity.ok(approvalGateService.releaseUnknownPayout(approvalId, reviewerId));
    }

    public record ConfirmPayoutRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
            String transactionId
    ) {
    }
}
