package io.mcpguard.settlement.guardrail;

import io.mcpguard.settlement.config.GuardrailProperties;
import io.mcpguard.settlement.domain.PaymentGatewayPort;
import io.mcpguard.settlement.domain.ProviderRefundLookup;
import io.mcpguard.settlement.telemetry.ToolExecutionAuditLog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Four-eyes resolution of payouts frozen as {@code PAYOUT_UNKNOWN}.
 *
 * <ol>
 *   <li>A reviewer proposes CONFIRM (money moved, with the provider transaction id) or RELEASE (money did not move).</li>
 *   <li>The proposal is checked against the gateway: CONFIRM needs a matching refund under the request's idempotency
 *       key, RELEASE needs the gateway to have no refund under that key.</li>
 *   <li>A different reviewer approves it. The gateway is checked again and the ledger change, the approval status,
 *       and the closed proposal commit in one transaction.</li>
 * </ol>
 */
@Service
public class PayoutResolutionService {

    private static final Logger log = LoggerFactory.getLogger(PayoutResolutionService.class);

    private final RefundApprovalOrderRepository approvalRepository;
    private final PayoutResolutionRepository resolutionRepository;
    private final PaymentGatewayPort paymentGateway;
    private final ApprovalGateService approvalGateService;
    private final ToolExecutionAuditLog auditLog;
    private final GuardrailProperties properties;
    private final TransactionTemplate txTemplate;

    public PayoutResolutionService(
            RefundApprovalOrderRepository approvalRepository,
            PayoutResolutionRepository resolutionRepository,
            PaymentGatewayPort paymentGateway,
            ApprovalGateService approvalGateService,
            ToolExecutionAuditLog auditLog,
            GuardrailProperties properties,
            PlatformTransactionManager transactionManager) {
        this.approvalRepository = approvalRepository;
        this.resolutionRepository = resolutionRepository;
        this.paymentGateway = paymentGateway;
        this.approvalGateService = approvalGateService;
        this.auditLog = auditLog;
        this.properties = properties;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    public PayoutResolutionView propose(
            String approvalId, String reviewerId, PayoutResolution.Action action, String transactionId) {
        long startNanos = System.nanoTime();
        String proposer = ApprovalGateService.requireReviewer(reviewerId);
        String txId = action == PayoutResolution.Action.CONFIRM
                ? ApprovalGateService.requireTransactionId(transactionId)
                : null;
        RefundApprovalOrder order = findApproval(approvalId);

        PayoutResolutionView alreadyResolved = alreadyResolved(order, action, txId);
        if (alreadyResolved != null) {
            return alreadyResolved;
        }
        if (order.getStatus() != ApprovalStatus.PAYOUT_UNKNOWN) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Only frozen payouts can be resolved; current state: " + order.getStatus());
        }
        if (txId != null && approvalRepository.existsByTransactionId(txId)) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Transaction id " + txId + " is already recorded on another refund");
        }
        verifyAgainstGateway(order, action, txId);

        PayoutResolution saved;
        try {
            saved = txTemplate.execute(status -> {
                RefundApprovalOrder latest = findApproval(approvalId);
                if (latest.getStatus() != ApprovalStatus.PAYOUT_UNKNOWN) {
                    throw new GuardrailExceptions.ApprovalStateConflictException(
                            "Only frozen payouts can be resolved; current state: " + latest.getStatus());
                }
                return resolutionRepository.saveAndFlush(
                        PayoutResolution.propose(approvalId, action, txId, proposer, Instant.now()));
            });
        } catch (DataIntegrityViolationException alreadyOpen) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Another payout resolution for " + approvalId + " is already awaiting review");
        }
        audit("propose_payout_resolution", order, action.name() + "_PROPOSED", proposer, startNanos);
        return PayoutResolutionView.of(saved, order.getStatus());
    }

    public ApprovalGateService.RefundDecision approve(String approvalId, String reviewerId) {
        String approver = ApprovalGateService.requireReviewer(reviewerId);
        PayoutResolution open = findOpen(approvalId);
        if (open.getProposedBy().toLowerCase(Locale.ROOT).equals(approver.toLowerCase(Locale.ROOT))) {
            throw new GuardrailExceptions.SeparationOfDutiesException(
                    "The reviewer who proposed this resolution cannot approve it");
        }
        RefundApprovalOrder order = findApproval(approvalId);
        if (order.getStatus() == ApprovalStatus.PAYOUT_UNKNOWN) {
            verifyAgainstGateway(order, open.getAction(), open.getTransactionId());
        }
        return open.getAction() == PayoutResolution.Action.CONFIRM
                ? approvalGateService.applyConfirmedPayout(approvalId, approver, open.getTransactionId(), open.getId())
                : approvalGateService.applyReleasedPayout(approvalId, approver, open.getId());
    }

    /** Closes the open proposal without changing the payout. The proposer may use this to withdraw it. */
    public PayoutResolutionView reject(String approvalId, String reviewerId) {
        long startNanos = System.nanoTime();
        String reviewer = ApprovalGateService.requireReviewer(reviewerId);
        PayoutResolution open = findOpen(approvalId);
        Integer closed = txTemplate.execute(status -> resolutionRepository.closeProposal(
                open.getId(), PayoutResolution.Status.REJECTED, reviewer, Instant.now()));
        if (closed == null || closed != 1) {
            throw new GuardrailExceptions.ApprovalStateConflictException("Payout resolution is no longer awaiting review");
        }
        RefundApprovalOrder order = findApproval(approvalId);
        audit("reject_payout_resolution", order, open.getAction().name() + "_REJECTED", reviewer, startNanos);
        PayoutResolution latest = resolutionRepository.findById(open.getId()).orElseThrow();
        return PayoutResolutionView.of(latest, order.getStatus());
    }

    public List<PayoutResolutionView> history(String approvalId) {
        RefundApprovalOrder order = findApproval(approvalId);
        return resolutionRepository.findByApprovalIdOrderByIdAsc(approvalId).stream()
                .map(r -> PayoutResolutionView.of(r, order.getStatus()))
                .toList();
    }

    private PayoutResolutionView alreadyResolved(
            RefundApprovalOrder order, PayoutResolution.Action action, String txId) {
        if (order.getStatus() == ApprovalStatus.EXECUTED) {
            if (action == PayoutResolution.Action.CONFIRM && txId.equals(order.getTransactionId())) {
                return PayoutResolutionView.settled(order, "ALREADY_SETTLED");
            }
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Refund " + order.getApprovalId() + " is already settled with transaction id "
                            + order.getTransactionId());
        }
        if (order.getStatus() == ApprovalStatus.PAYOUT_RELEASED) {
            if (action == PayoutResolution.Action.RELEASE) {
                return PayoutResolutionView.settled(order, "ALREADY_RELEASED");
            }
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Refund " + order.getApprovalId() + " was already released and cannot be confirmed");
        }
        return null;
    }

    private void verifyAgainstGateway(RefundApprovalOrder order, PayoutResolution.Action action, String txId) {
        ProviderRefundLookup lookup = readProvider(order);
        if (action == PayoutResolution.Action.CONFIRM) {
            requireSucceeded(order, lookup, txId);
            return;
        }
        if (paymentGateway.cancelSupported() && lookup.state() != ProviderRefundLookup.State.SUCCEEDED) {
            try {
                paymentGateway.cancelRefund(order.getOrderId(), order.getIdempotencyKey());
            } catch (PaymentGatewayPort.CancelNotSupportedException unsupported) {
                requireReleaseWithoutCancel(order, lookup);
                return;
            } catch (RuntimeException gatewayFailure) {
                throw unavailable(order, gatewayFailure);
            }
            lookup = readProvider(order);
        }
        requireSafeToRelease(order, lookup);
    }

    private ProviderRefundLookup readProvider(RefundApprovalOrder order) {
        try {
            return paymentGateway.lookupRefund(order.getOrderId(), order.getIdempotencyKey());
        } catch (RuntimeException gatewayFailure) {
            throw unavailable(order, gatewayFailure);
        }
    }

    private void requireSucceeded(RefundApprovalOrder order, ProviderRefundLookup lookup, String txId) {
        if (lookup.state() == ProviderRefundLookup.State.PENDING) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway refund for " + order.getApprovalId() + " is still processing; try again later");
        }
        if (lookup.state() == ProviderRefundLookup.State.NOT_FOUND || lookup.refund() == null) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway has no refund for " + order.getApprovalId() + "; it cannot be confirmed");
        }
        if (lookup.state() != ProviderRefundLookup.State.SUCCEEDED) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway refund for " + order.getApprovalId() + " is " + lookup.state() + "; it cannot be confirmed");
        }
        PaymentGatewayPort.PaymentExecutionResult refund = lookup.refund();
        if (!refund.transactionId().equals(txId)) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway transaction id for " + order.getApprovalId() + " does not match " + txId);
        }
        if (refund.amount().compareTo(order.getAmount()) != 0
                || !refund.currency().equalsIgnoreCase(order.getCurrency())) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway amount or currency for " + order.getApprovalId() + " differs from the approved snapshot");
        }
    }

    private void requireSafeToRelease(RefundApprovalOrder order, ProviderRefundLookup lookup) {
        if (lookup.state() == ProviderRefundLookup.State.SUCCEEDED) {
            String providerTx = lookup.refund() == null ? "unknown" : lookup.refund().transactionId();
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway shows refund " + providerTx + " for " + order.getApprovalId()
                            + "; confirm it instead of releasing");
        }
        if (lookup.state() == ProviderRefundLookup.State.PENDING) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway refund for " + order.getApprovalId() + " is still processing; try again later");
        }
        if (lookup.state() == ProviderRefundLookup.State.CANCELLED
                || (lookup.state() == ProviderRefundLookup.State.FAILED && lookup.terminal())) {
            return;
        }
        if (lookup.state() == ProviderRefundLookup.State.FAILED) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway failure for " + order.getApprovalId() + " is not terminal; it cannot be released yet");
        }
        requireReleaseWithoutCancel(order, lookup);
    }

    private void requireReleaseWithoutCancel(RefundApprovalOrder order, ProviderRefundLookup lookup) {
        if (lookup.state() != ProviderRefundLookup.State.NOT_FOUND) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway refund for " + order.getApprovalId() + " is " + lookup.state()
                            + "; it cannot be released without a terminal provider result");
        }
        Instant frozenSince = order.getReviewedAt() != null ? order.getReviewedAt() : order.getCreatedAt();
        Instant eligibleAt = frozenSince.plus(Duration.ofHours(properties.provider().settlementWindowHours()));
        if (Instant.now().isBefore(eligibleAt)) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "The gateway has no refund for " + order.getApprovalId()
                            + ", but the settlement window has not elapsed; it cannot be released yet");
        }
    }

    private static GuardrailExceptions.GatewayVerificationUnavailableException unavailable(
            RefundApprovalOrder order, RuntimeException gatewayFailure) {
        return new GuardrailExceptions.GatewayVerificationUnavailableException(
                "Payment gateway lookup failed for " + order.getApprovalId() + "; try again later", gatewayFailure);
    }

    private RefundApprovalOrder findApproval(String approvalId) {
        return approvalRepository.findByApprovalId(approvalId)
                .orElseThrow(() -> new GuardrailExceptions.ApprovalNotFoundException(
                        "Approval request not found: " + approvalId));
    }

    private PayoutResolution findOpen(String approvalId) {
        return resolutionRepository.findByOpenApprovalId(approvalId)
                .orElseThrow(() -> new GuardrailExceptions.ApprovalStateConflictException(
                        "No payout resolution for " + approvalId + " is awaiting review"));
    }

    private void audit(String operation, RefundApprovalOrder order, String rule, String reviewer, long startNanos) {
        try {
            auditLog.record(operation, order.getOrderId(), order.getApprovalId(), order.getAmount(),
                    order.getStatus().name(), rule, reviewer,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        } catch (RuntimeException ex) {
            log.warn("Audit log write failed: operation={}, approvalId={}", operation, order.getApprovalId(), ex);
        }
    }

    public record PayoutResolutionView(
            Long resolutionId,
            String approvalId,
            String action,
            String transactionId,
            String status,
            String proposedBy,
            Instant proposedAt,
            String decidedBy,
            Instant decidedAt,
            String approvalStatus
    ) {
        static PayoutResolutionView of(PayoutResolution r, ApprovalStatus approvalStatus) {
            return new PayoutResolutionView(
                    r.getId(),
                    r.getApprovalId(),
                    r.getAction().name(),
                    r.getTransactionId(),
                    r.getStatus().name(),
                    r.getProposedBy(),
                    r.getProposedAt(),
                    r.getDecidedBy(),
                    r.getDecidedAt(),
                    approvalStatus.name());
        }

        static PayoutResolutionView settled(RefundApprovalOrder order, String status) {
            return new PayoutResolutionView(
                    null,
                    order.getApprovalId(),
                    null,
                    order.getTransactionId(),
                    status,
                    null,
                    null,
                    order.getReviewerId(),
                    order.getReviewedAt(),
                    order.getStatus().name());
        }
    }
}
