package io.mcpguard.settlement.guardrail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finance reviewer endpoints. The reviewer identity is the subject of the verified JWT, never a request header.
 *
 * <ul>
 *   <li>approve / reject: decide a paused refund. No amount is accepted; execution uses the signed snapshot.</li>
 *   <li>confirm-payout / release-payout: propose how to resolve a frozen payout. Nothing changes until a second
 *       reviewer calls payout-resolution/approve.</li>
 * </ul>
 */
@RestController
public class ApprovalRestController {

    private final ApprovalGateService approvalGateService;
    private final PayoutResolutionService resolutionService;

    public ApprovalRestController(ApprovalGateService approvalGateService, PayoutResolutionService resolutionService) {
        this.approvalGateService = approvalGateService;
        this.resolutionService = resolutionService;
    }

    @PostMapping("/api/approvals/{approvalId}/approve")
    public ResponseEntity<ApprovalGateService.RefundDecision> approve(
            @PathVariable String approvalId, Authentication reviewer) {
        return ResponseEntity.ok(approvalGateService.approve(approvalId, reviewer.getName()));
    }

    @PostMapping("/api/approvals/{approvalId}/reject")
    public ResponseEntity<ApprovalGateService.RefundDecision> reject(
            @PathVariable String approvalId, Authentication reviewer) {
        return ResponseEntity.ok(approvalGateService.reject(approvalId, reviewer.getName()));
    }

    @GetMapping("/api/approvals/{approvalId}")
    public ResponseEntity<ApprovalGateService.ApprovalStatusView> getStatus(@PathVariable String approvalId) {
        return ResponseEntity.ok(approvalGateService.getApprovalStatus(approvalId));
    }

    @PostMapping("/api/approvals/{approvalId}/confirm-payout")
    public ResponseEntity<PayoutResolutionService.PayoutResolutionView> proposeConfirm(
            @PathVariable String approvalId,
            @Valid @RequestBody ConfirmPayoutRequest request,
            Authentication reviewer) {
        return proposalResponse(resolutionService.propose(
                approvalId, reviewer.getName(), PayoutResolution.Action.CONFIRM, request.transactionId()));
    }

    @PostMapping("/api/approvals/{approvalId}/release-payout")
    public ResponseEntity<PayoutResolutionService.PayoutResolutionView> proposeRelease(
            @PathVariable String approvalId, Authentication reviewer) {
        return proposalResponse(resolutionService.propose(
                approvalId, reviewer.getName(), PayoutResolution.Action.RELEASE, null));
    }

    @PostMapping("/api/approvals/{approvalId}/payout-resolution/approve")
    public ResponseEntity<ApprovalGateService.RefundDecision> approveResolution(
            @PathVariable String approvalId, Authentication reviewer) {
        return ResponseEntity.ok(resolutionService.approve(approvalId, reviewer.getName()));
    }

    @PostMapping("/api/approvals/{approvalId}/payout-resolution/reject")
    public ResponseEntity<PayoutResolutionService.PayoutResolutionView> rejectResolution(
            @PathVariable String approvalId, Authentication reviewer) {
        return ResponseEntity.ok(resolutionService.reject(approvalId, reviewer.getName()));
    }

    @GetMapping("/api/approvals/{approvalId}/payout-resolutions")
    public ResponseEntity<List<PayoutResolutionService.PayoutResolutionView>> resolutionHistory(
            @PathVariable String approvalId) {
        return ResponseEntity.ok(resolutionService.history(approvalId));
    }

    private static ResponseEntity<PayoutResolutionService.PayoutResolutionView> proposalResponse(
            PayoutResolutionService.PayoutResolutionView view) {
        HttpStatus status = PayoutResolution.Status.PROPOSED.name().equals(view.status())
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(view);
    }

    public record ConfirmPayoutRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
            String transactionId
    ) {
    }
}
