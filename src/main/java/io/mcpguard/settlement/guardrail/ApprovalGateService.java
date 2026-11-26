package io.mcpguard.settlement.guardrail;

import io.mcpguard.settlement.config.GuardrailProperties;
import io.mcpguard.settlement.domain.DeterministicReconciler;
import io.mcpguard.settlement.domain.OrderAnchorLockService;
import io.mcpguard.settlement.domain.OrderSettlementAnchor;
import io.mcpguard.settlement.domain.OrderSettlementAnchorRepository;
import io.mcpguard.settlement.domain.PaymentGatewayPort;
import io.mcpguard.settlement.domain.SettlementInvoiceItem;
import io.mcpguard.settlement.domain.SettlementInvoiceRepository;
import io.mcpguard.settlement.domain.SnapshotHasher;
import io.mcpguard.settlement.telemetry.ToolExecutionAuditLog;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Core two-phase financial settlement guardrail:
 * - Enforces local-first hidden mandates (single transaction limit + rolling 24h anti-smurfing cap).
 * - Rejects refunds for unknown orders or beyond the order's remaining refundable balance.
 * - Serializes per-order evaluations with SELECT ... FOR UPDATE pessimistic locks.
 * - Reserves allowance in the database (EXECUTING) before any money moves, then calls the payment
 *   gateway outside of any database transaction so a gateway timeout can never roll back a claimed payout.
 * - Keeps the idempotency cache aligned with the terminal approval state.
 * - Emits opaque pause messages so adversarial agents cannot binary-search internal thresholds.
 * - Verifies HMAC parameter snapshots and atomic CAS transitions before human-approved payouts.
 */
@Service
public class ApprovalGateService {

    public static final String OPAQUE_HOLD_MESSAGE = "Paused for human sign-off";
    public static final String REUSE_KEY_MESSAGE =
            "Request is still in progress. Retry with the same idempotency key. Do not create a new one.";
    public static final String FROZEN_PAYOUT_MESSAGE =
            "Payout is frozen for manual reconciliation. Do not submit a new idempotency key.";
    public static final String EXPIRED_MESSAGE = "Approval request has expired and must be re-submitted";
    public static final String DUPLICATE_IN_FLIGHT_MESSAGE =
            "An identical refund for this order is already in progress. Track this approvalId; do not submit it again.";
    public static final String RULE_IN_FLIGHT_DUPLICATE = "IN_FLIGHT_DUPLICATE_LINKED";
    public static final String RULE_AUTO_UNDER_MANDATE = "AUTO_APPROVED_UNDER_MANDATE";
    public static final String RULE_SINGLE_LIMIT = "SINGLE_LIMIT_EXCEEDED";
    public static final String RULE_ROLLING_24H_VELOCITY = "ROLLING_24H_VELOCITY_EXCEEDED";

    private static final Pattern TRANSACTION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(ApprovalGateService.class);

    /** Statuses whose amount has left, or may have left, the merchant account within the window. */
    private static final List<ApprovalStatus> VELOCITY_STATUSES = List.of(
            ApprovalStatus.PENDING_APPROVAL,
            ApprovalStatus.EXECUTING,
            ApprovalStatus.EXECUTED,
            ApprovalStatus.PAYOUT_UNKNOWN);

    /** Statuses reserved against the refundable balance but not yet reflected in already_refunded_amount. */
    private static final List<ApprovalStatus> RESERVED_STATUSES = List.of(
            ApprovalStatus.PENDING_APPROVAL,
            ApprovalStatus.EXECUTING,
            ApprovalStatus.PAYOUT_UNKNOWN);

    private final GuardrailProperties properties;
    private final IdempotencyKeyStore idempotencyKeyStore;
    private final OrderAnchorLockService anchorLockService;
    private final OrderSettlementAnchorRepository anchorRepository;
    private final RefundApprovalOrderRepository approvalRepository;
    private final SettlementInvoiceRepository invoiceRepository;
    private final PaymentGatewayPort paymentGateway;
    private final ApprovalWebhookNotifier webhookNotifier;
    private final ToolExecutionAuditLog auditLog;
    private final SnapshotHasher snapshotHasher;
    private final PayoutResolutionRepository resolutionRepository;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate requiresNewTx;

    public ApprovalGateService(
            GuardrailProperties properties,
            IdempotencyKeyStore idempotencyKeyStore,
            OrderAnchorLockService anchorLockService,
            OrderSettlementAnchorRepository anchorRepository,
            RefundApprovalOrderRepository approvalRepository,
            SettlementInvoiceRepository invoiceRepository,
            PaymentGatewayPort paymentGateway,
            ApprovalWebhookNotifier webhookNotifier,
            ToolExecutionAuditLog auditLog,
            SnapshotHasher snapshotHasher,
            PayoutResolutionRepository resolutionRepository,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.idempotencyKeyStore = idempotencyKeyStore;
        this.anchorLockService = anchorLockService;
        this.anchorRepository = anchorRepository;
        this.approvalRepository = approvalRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentGateway = paymentGateway;
        this.webhookNotifier = webhookNotifier;
        this.auditLog = auditLog;
        this.snapshotHasher = snapshotHasher;
        this.resolutionRepository = resolutionRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public RefundDecision submitRefund(RefundCommand rawCommand) {
        long startNanos = System.nanoTime();
        RefundCommand command = validateAndNormalize(rawCommand);
        return submitNormalized(command, startNanos, true);
    }

    private RefundDecision submitNormalized(RefundCommand command, long startNanos, boolean allowSuspendedRecovery) {
        String snapshotHash = snapshotHasher.hash(
                command.orderId(), command.amount(), command.currency(), command.reasonCode());
        String requestFingerprint = computeRequestFingerprint(command);

        IdempotencyKeyStore.ClaimResult claim = idempotencyKeyStore.tryClaimOrReplay(
                command.idempotencyKey(), requestFingerprint);
        if (claim.outcome() == IdempotencyKeyStore.ClaimOutcome.REPLAY) {
            return replay(command, claim.cachedResponseJson(), startNanos);
        }
        if (claim.outcome() == IdempotencyKeyStore.ClaimOutcome.IN_PROGRESS) {
            return inProgressDecision(command);
        }
        if (claim.outcome() == IdempotencyKeyStore.ClaimOutcome.SUSPENDED) {
            Optional<RefundApprovalOrder> owned = ownedOrLinkedApproval(command.idempotencyKey());
            if (owned.isPresent()) {
                return toDecision(owned.get(), messageFor(owned.get().getStatus()));
            }
            if (!allowSuspendedRecovery) {
                throw new GuardrailExceptions.IdempotencyConflictException(
                        "Idempotency key '" + command.idempotencyKey() + "' is frozen; reconcile it before retrying.");
            }
            // No approval belongs or is linked to this key, so nothing was reserved and the gateway was never called.
            if (idempotencyKeyStore.releaseSuspended(command.idempotencyKey())) {
                log.warn("Released suspended idempotency key {} that never reserved a payout", command.idempotencyKey());
            }
            return submitNormalized(command, startNanos, false);
        }

        LockedEvaluation eval = null;
        RefundDecision decision = null;
        boolean outcomeCommitted = false;
        try {
            // Check existence before creating a lock anchor, so unknown order ids leave no rows behind.
            if (invoiceRepository.findByOrderId(command.orderId()).isEmpty()) {
                throw new GuardrailExceptions.InvalidSettlementRequestException(
                        "Order is not eligible for refund: " + command.orderId());
            }
            anchorLockService.ensureAnchorExists(command.orderId());
            eval = txTemplate.execute(status -> evaluateUnderOrderLock(command, snapshotHash));
            if (eval == null) {
                throw new IllegalStateException("Refund evaluation returned no decision");
            }
            decision = eval.decision();
            if (eval.payoutReserved()) {
                DispatchResult settled = dispatchReservedPayout(
                        decision.approvalId(), command.orderId(), command.amount(), command.currency(),
                        command.idempotencyKey(), null);
                decision = new RefundDecision(
                        ApprovalStatus.EXECUTED.name(),
                        decision.approvalId(),
                        settled.transactionId(),
                        command.orderId(),
                        command.amount(),
                        command.currency(),
                        "Auto-executed within settlement guardrail policy"
                );
            }
            // From here on the outcome is committed in the database: a new hold, a link to an existing
            // request, or a settled payout. The caller must receive it even if storing the receipt fails.
            outcomeCommitted = true;
            rememberDecision(command.idempotencyKey(), decision);
            safeAudit(
                    "issue_customer_refund",
                    command.orderId(),
                    decision.approvalId(),
                    command.amount(),
                    decision.status(),
                    eval.linksExistingRequest() ? RULE_IN_FLIGHT_DUPLICATE : eval.internalRuleCode(),
                    eval.reviewerMarker(),
                    startNanos);
            return decision;
        } catch (GuardrailExceptions.InvalidSettlementRequestException rejectedBeforeSideEffects) {
            idempotencyKeyStore.releaseInProgress(command.idempotencyKey());
            throw rejectedBeforeSideEffects;
        } catch (GuardrailExceptions.PayoutOutcomeUnknownException unknown) {
            idempotencyKeyStore.markUnknownSuspended(
                    command.idempotencyKey(),
                    unknownDiagnostic(decision == null ? null : decision.approvalId()));
            throw unknown;
        } catch (RuntimeException unexpected) {
            if (outcomeCommitted) {
                rememberQuietly(command.idempotencyKey(), decision);
                log.warn("Refund outcome {} for {} is committed but its idempotency receipt was not stored",
                        decision.status(), decision.approvalId(), unexpected);
                return decision;
            }
            if (eval != null && eval.payoutReserved()) {
                idempotencyKeyStore.markUnknownSuspended(
                        command.idempotencyKey(),
                        unknownDiagnostic(decision == null ? null : decision.approvalId()));
            } else {
                idempotencyKeyStore.releaseInProgress(command.idempotencyKey());
            }
            throw unexpected;
        }
    }

    public RefundDecision approve(String approvalId, String reviewerId) {
        long startNanos = System.nanoTime();
        String reviewer = requireReviewer(reviewerId);
        // Persist an overdue expiry before the claim transaction opens, so it never holds two pool connections.
        expireBeforeClaim(approvalId);

        ApprovalClaim claim = txTemplate.execute(status -> {
            RefundApprovalOrder order = findOrThrow(approvalId);
            Instant now = Instant.now();
            rejectIfOverdue(order, now);

            if (order.getStatus() == ApprovalStatus.EXECUTED) {
                return new ApprovalClaim(order, false, null);
            }
            if (order.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
                throw new GuardrailExceptions.ApprovalStateConflictException(
                        "Approval request cannot be approved from state: " + order.getStatus());
            }

            verifySnapshotIntegrity(order);

            int updated = approvalRepository.claimPendingForExecution(approvalId, reviewer, now);
            RefundApprovalOrder latest = findOrThrow(approvalId);
            if (updated == 1) {
                return new ApprovalClaim(latest, true, null);
            }
            if (latest.getStatus() == ApprovalStatus.EXECUTED) {
                return new ApprovalClaim(latest, false, null);
            }
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Concurrent state change prevented approval: " + latest.getStatus());
        });

        RefundApprovalOrder order = claim.order();
        if (!claim.claimedByThisCall()) {
            RefundDecision decision = toDecision(order, "Already executed");
            idempotencyKeyStore.recordTerminalDecision(order.getIdempotencyKey(), decision);
            return decision;
        }

        try {
            DispatchResult settled = dispatchReservedPayout(
                    order.getApprovalId(), order.getOrderId(), order.getAmount(), order.getCurrency(),
                    order.getIdempotencyKey(), reviewer);
            RefundDecision decision = new RefundDecision(
                    ApprovalStatus.EXECUTED.name(),
                    order.getApprovalId(),
                    settled.transactionId(),
                    order.getOrderId(),
                    order.getAmount(),
                    order.getCurrency(),
                    "Approved and executed once"
            );
            idempotencyKeyStore.recordTerminalDecision(order.getIdempotencyKey(), decision);
            safeAudit(
                    "approve_paused_refund",
                    order.getOrderId(),
                    order.getApprovalId(),
                    order.getAmount(),
                    ApprovalStatus.EXECUTED.name(),
                    "HUMAN_REVIEWER_APPROVED",
                    reviewer,
                    startNanos);
            webhookNotifier.publishStaged(settled.outboxId());
            return decision;
        } catch (GuardrailExceptions.PayoutOutcomeUnknownException unknown) {
            RefundApprovalOrder frozen = findOrThrow(order.getApprovalId());
            idempotencyKeyStore.recordTerminalDecision(
                    order.getIdempotencyKey(),
                    toDecision(frozen, FROZEN_PAYOUT_MESSAGE));
            throw unknown;
        }
    }

    public RefundDecision reject(String approvalId, String reviewerId) {
        long startNanos = System.nanoTime();
        String reviewer = requireReviewer(reviewerId);
        // Persist an overdue expiry before the claim transaction opens, so it never holds two pool connections.
        expireBeforeClaim(approvalId);

        ApprovalClaim claim = txTemplate.execute(status -> {
            RefundApprovalOrder order = findOrThrow(approvalId);
            Instant now = Instant.now();
            rejectIfOverdue(order, now);

            if (order.getStatus() == ApprovalStatus.REJECTED) {
                return new ApprovalClaim(order, false, null);
            }
            if (order.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
                throw new GuardrailExceptions.ApprovalStateConflictException(
                        "Approval request cannot be rejected from state: " + order.getStatus());
            }

            int updated = approvalRepository.claimPendingForRejection(approvalId, reviewer, now);
            RefundApprovalOrder latest = findOrThrow(approvalId);
            if (updated == 1) {
                Long outboxId = webhookNotifier.stage(new ApprovalWebhookNotifier.ApprovalDecisionEvent(
                        latest.getApprovalId(),
                        latest.getOrderId(),
                        latest.getAmount(),
                        latest.getCurrency(),
                        ApprovalStatus.REJECTED,
                        null,
                        reviewer,
                        now));
                return new ApprovalClaim(latest, true, outboxId);
            }
            if (latest.getStatus() == ApprovalStatus.REJECTED) {
                return new ApprovalClaim(latest, false, null);
            }
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Concurrent state change prevented rejection: " + latest.getStatus());
        });

        RefundApprovalOrder rejectedOrder = claim.order();
        if (!claim.claimedByThisCall()) {
            RefundDecision decision = toDecision(rejectedOrder, "Already rejected");
            idempotencyKeyStore.recordTerminalDecision(rejectedOrder.getIdempotencyKey(), decision);
            return decision;
        }

        RefundDecision decision = toDecision(rejectedOrder, "Rejected by finance reviewer; in-flight allowance released");
        idempotencyKeyStore.recordTerminalDecision(rejectedOrder.getIdempotencyKey(), decision);
        safeAudit(
                "reject_paused_refund",
                rejectedOrder.getOrderId(),
                rejectedOrder.getApprovalId(),
                rejectedOrder.getAmount(),
                ApprovalStatus.REJECTED.name(),
                "HUMAN_REVIEWER_REJECTED",
                reviewer,
                startNanos);
        webhookNotifier.publishStaged(claim.outboxId());
        return decision;
    }

    /**
     * Books a frozen payout as settled. Called only by {@link PayoutResolutionService} after a second reviewer
     * approved a CONFIRM resolution and the gateway lookup matched. Never calls the gateway refund endpoint.
     * The resolution is closed in the same transaction as the status change.
     */
    RefundDecision applyConfirmedPayout(String approvalId, String approverId, String transactionId, long resolutionId) {
        long startNanos = System.nanoTime();
        String reviewer = requireReviewer(approverId);
        String txId = requireTransactionId(transactionId);

        Resolved resolved;
        try {
            resolved = txTemplate.execute(status -> {
                RefundApprovalOrder order = findOrThrow(approvalId);
                if (order.getStatus() == ApprovalStatus.EXECUTED) {
                    return new Resolved(toDecision(order, "Already executed"), order, false, null);
                }
                if (order.getStatus() != ApprovalStatus.PAYOUT_UNKNOWN) {
                    throw new GuardrailExceptions.ApprovalStateConflictException(
                            "Payout cannot be confirmed from state: " + order.getStatus());
                }
                verifySnapshotIntegrity(order);
                lockAnchor(order.getOrderId());
                if (approvalRepository.existsByTransactionId(txId)) {
                    throw new GuardrailExceptions.ApprovalStateConflictException(
                            "Transaction id " + txId + " is already recorded on another refund");
                }
                Instant now = Instant.now();
                int settled = approvalRepository.settleUnknownPayout(approvalId, txId, reviewer, now);
                if (settled != 1) {
                    RefundApprovalOrder latest = findOrThrow(approvalId);
                    if (latest.getStatus() == ApprovalStatus.EXECUTED) {
                        return new Resolved(toDecision(latest, "Already executed"), latest, false, null);
                    }
                    throw new GuardrailExceptions.ApprovalStateConflictException(
                            "Concurrent state change prevented payout confirmation: " + latest.getStatus());
                }
                int updated = invoiceRepository.incrementRefundedAmount(order.getOrderId(), order.getAmount());
                if (updated != 1) {
                    throw new GuardrailExceptions.ApprovalStateConflictException(
                            "Settled amount exceeds the remaining captured balance for " + order.getOrderId());
                }
                closeResolution(resolutionId, reviewer, now);
                RefundApprovalOrder latest = findOrThrow(approvalId);
                Long outboxId = webhookNotifier.stage(new ApprovalWebhookNotifier.ApprovalDecisionEvent(
                        latest.getApprovalId(),
                        latest.getOrderId(),
                        latest.getAmount(),
                        latest.getCurrency(),
                        ApprovalStatus.EXECUTED,
                        latest.getTransactionId(),
                        reviewer,
                        now));
                return new Resolved(
                        toDecision(latest, "Settled after manual gateway reconciliation"),
                        latest,
                        true,
                        outboxId);
            });
        } catch (DataIntegrityViolationException duplicateTransactionId) {
            // Two confirmations on different orders raced with the same provider transaction id.
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Transaction id " + txId + " is already recorded on another refund");
        }

        idempotencyKeyStore.recordTerminalDecision(resolved.order().getIdempotencyKey(), resolved.decision());
        if (resolved.changed()) {
            safeAudit(
                    "confirm_unknown_payout",
                    resolved.order().getOrderId(),
                    resolved.order().getApprovalId(),
                    resolved.order().getAmount(),
                    ApprovalStatus.EXECUTED.name(),
                    "FOUR_EYES_CONFIRMED_GATEWAY_SETTLEMENT",
                    reviewer,
                    startNanos);
            webhookNotifier.publishStaged(resolved.outboxId());
        }
        return resolved.decision();
    }

    /**
     * Releases a frozen reservation. Called only by {@link PayoutResolutionService} after a second reviewer
     * approved a RELEASE resolution and the gateway lookup showed no refund under this idempotency key.
     */
    RefundDecision applyReleasedPayout(String approvalId, String approverId, long resolutionId) {
        long startNanos = System.nanoTime();
        String reviewer = requireReviewer(approverId);

        Resolved resolved = txTemplate.execute(status -> {
            RefundApprovalOrder order = findOrThrow(approvalId);
            if (order.getStatus() == ApprovalStatus.PAYOUT_RELEASED) {
                return new Resolved(toDecision(order, "Already released"), order, false, null);
            }
            if (order.getStatus() != ApprovalStatus.PAYOUT_UNKNOWN) {
                throw new GuardrailExceptions.ApprovalStateConflictException(
                        "Payout cannot be released from state: " + order.getStatus());
            }
            verifySnapshotIntegrity(order);
            lockAnchor(order.getOrderId());
            Instant now = Instant.now();
            int updated = approvalRepository.releaseUnknownPayout(approvalId, reviewer, now);
            RefundApprovalOrder latest = findOrThrow(approvalId);
            if (updated != 1) {
                if (latest.getStatus() == ApprovalStatus.PAYOUT_RELEASED) {
                    return new Resolved(toDecision(latest, "Already released"), latest, false, null);
                }
                throw new GuardrailExceptions.ApprovalStateConflictException(
                        "Concurrent state change prevented payout release: " + latest.getStatus());
            }
            closeResolution(resolutionId, reviewer, now);
            Long outboxId = webhookNotifier.stage(new ApprovalWebhookNotifier.ApprovalDecisionEvent(
                    latest.getApprovalId(),
                    latest.getOrderId(),
                    latest.getAmount(),
                    latest.getCurrency(),
                    ApprovalStatus.PAYOUT_RELEASED,
                    null,
                    reviewer,
                    now));
            return new Resolved(
                    toDecision(latest, "Released after confirming the gateway did not move money"),
                    latest,
                    true,
                    outboxId);
        });

        idempotencyKeyStore.recordTerminalDecision(resolved.order().getIdempotencyKey(), resolved.decision());
        if (resolved.changed()) {
            safeAudit(
                    "release_unknown_payout",
                    resolved.order().getOrderId(),
                    resolved.order().getApprovalId(),
                    resolved.order().getAmount(),
                    ApprovalStatus.PAYOUT_RELEASED.name(),
                    "FOUR_EYES_CONFIRMED_NO_PAYOUT",
                    reviewer,
                    startNanos);
            webhookNotifier.publishStaged(resolved.outboxId());
        }
        return resolved.decision();
    }

    public ApprovalStatusView getApprovalStatus(String approvalId) {
        LoadedStatus loaded = txTemplate.execute(status -> loadStatus(approvalId));
        if (loaded == null) {
            throw new GuardrailExceptions.ApprovalNotFoundException("Approval request not found: " + approvalId);
        }
        if (ApprovalStatus.EXPIRED.name().equals(loaded.view().status())) {
            idempotencyKeyStore.recordTerminalDecision(
                    loaded.idempotencyKey(),
                    new RefundDecision(
                            loaded.view().status(),
                            loaded.view().approvalId(),
                            loaded.view().refundTxId(),
                            loaded.view().orderId(),
                            loaded.view().amount(),
                            loaded.view().currency(),
                            EXPIRED_MESSAGE));
        }
        return loaded.view();
    }

    private RefundDecision replay(RefundCommand command, String cachedJson, long startNanos) {
        RefundDecision cached = idempotencyKeyStore.deserialize(cachedJson, RefundDecision.class);
        if (isRefreshableCachedStatus(cached.status()) && StringUtils.hasText(cached.approvalId())) {
            try {
                ApprovalStatusView live = getApprovalStatus(cached.approvalId());
                if (!cached.status().equals(live.status())) {
                    RefundDecision refreshed = new RefundDecision(
                            live.status(),
                            live.approvalId(),
                            live.refundTxId(),
                            live.orderId(),
                            live.amount(),
                            live.currency(),
                            messageFor(ApprovalStatus.valueOf(live.status())));
                    idempotencyKeyStore.recordTerminalDecision(command.idempotencyKey(), refreshed);
                    safeAudit(
                            "issue_customer_refund",
                            command.orderId(),
                            refreshed.approvalId(),
                            command.amount(),
                            refreshed.status(),
                            "IDEMPOTENCY_CACHE_HIT",
                            null,
                            startNanos);
                    return refreshed;
                }
            } catch (GuardrailExceptions.ApprovalNotFoundException missing) {
                log.warn("Cached approval {} no longer exists for idempotency key {}",
                        cached.approvalId(), command.idempotencyKey());
            }
        }
        safeAudit(
                "issue_customer_refund",
                command.orderId(),
                cached.approvalId(),
                command.amount(),
                cached.status(),
                "IDEMPOTENCY_CACHE_HIT",
                null,
                startNanos);
        return cached;
    }

    private RefundDecision inProgressDecision(RefundCommand command) {
        return ownedOrLinkedApproval(command.idempotencyKey())
                .map(order -> toDecision(order, REUSE_KEY_MESSAGE))
                .orElseThrow(() -> new GuardrailExceptions.IdempotencyInProgressException(
                        "Request with idempotency key '" + command.idempotencyKey()
                                + "' is still in progress. Retry with the same idempotency key. Do not create a new one."));
    }

    private LoadedStatus loadStatus(String approvalId) {
        RefundApprovalOrder order = findOrThrow(approvalId);
        Instant now = Instant.now();
        if (order.getStatus() == ApprovalStatus.PENDING_APPROVAL && !order.getExpiresAt().isAfter(now)) {
            approvalRepository.expireOverdueForOrder(order.getOrderId(), now);
            order = findOrThrow(approvalId);
        }

        BigDecimal rolling24hUsed = DeterministicReconciler.scale(
                approvalRepository.sumAmountInWindow(
                        order.getOrderId(),
                        now.minus(Duration.ofHours(24)),
                        VELOCITY_STATUSES));
        BigDecimal refundableAmount = refundableAmount(order.getOrderId());
        return new LoadedStatus(
                new ApprovalStatusView(
                        order.getApprovalId(),
                        order.getOrderId(),
                        order.getAmount(),
                        order.getCurrency(),
                        order.getReasonCode(),
                        order.getInternalRuleCode(),
                        order.getStatus().name(),
                        order.getTransactionId(),
                        order.getReviewerId(),
                        order.getExpiresAt(),
                        rolling24hUsed,
                        refundableAmount),
                order.getIdempotencyKey());
    }

    private BigDecimal refundableAmount(String orderId) {
        SettlementInvoiceItem invoice = invoiceRepository.findByOrderId(orderId).orElse(null);
        if (invoice == null) {
            return DeterministicReconciler.scale(BigDecimal.ZERO);
        }
        BigDecimal reserved = DeterministicReconciler.scale(
                approvalRepository.sumAmountByStatuses(orderId, RESERVED_STATUSES));
        return DeterministicReconciler.scale(invoice.getCapturedAmount()
                .subtract(invoice.getAlreadyRefundedAmount())
                .subtract(reserved));
    }

    private LockedEvaluation evaluateUnderOrderLock(RefundCommand command, String snapshotHash) {
        Instant now = Instant.now();
        OrderSettlementAnchor anchor = anchorRepository.findByOrderIdForUpdate(command.orderId())
                .orElseThrow(() -> new IllegalStateException("Missing order lock anchor for " + command.orderId()));
        anchor.touch(now);

        approvalRepository.expireOverdueForOrder(command.orderId(), now);

        SettlementInvoiceItem invoice = invoiceRepository.findByOrderId(command.orderId())
                .orElseThrow(() -> new GuardrailExceptions.InvalidSettlementRequestException(
                        "Order is not eligible for refund: " + command.orderId()));
        if (!invoice.getCurrency().equalsIgnoreCase(command.currency())) {
            throw new GuardrailExceptions.InvalidSettlementRequestException(
                    "Refund currency does not match the order currency for " + command.orderId());
        }

        // An identical request that is still pending, executing, or frozen is the same refund retried under a
        // new key. Point the caller at it instead of reserving and paying a second time.
        List<RefundApprovalOrder> inFlight = approvalRepository.findInFlightDuplicates(
                command.orderId(), command.amount(), command.currency(), command.reasonCode(), RESERVED_STATUSES);
        if (!inFlight.isEmpty()) {
            RefundApprovalOrder existing = inFlight.get(0);
            // Record the link with the evaluation, so recovery can find it even if the receipt is never written.
            idempotencyKeyStore.linkToApproval(command.idempotencyKey(), existing.getApprovalId());
            String message = existing.getStatus() == ApprovalStatus.PAYOUT_UNKNOWN
                    ? FROZEN_PAYOUT_MESSAGE
                    : DUPLICATE_IN_FLIGHT_MESSAGE;
            return new LockedEvaluation(
                    toDecision(existing, message),
                    false,
                    true,
                    existing.getInternalRuleCode(),
                    null);
        }

        BigDecimal reserved = DeterministicReconciler.scale(
                approvalRepository.sumAmountByStatuses(command.orderId(), RESERVED_STATUSES));
        BigDecimal refundable = DeterministicReconciler.scale(invoice.getCapturedAmount()
                .subtract(invoice.getAlreadyRefundedAmount())
                .subtract(reserved));
        if (command.amount().compareTo(refundable) > 0) {
            throw new GuardrailExceptions.InvalidSettlementRequestException(
                    "Refund amount exceeds the remaining refundable balance for " + command.orderId());
        }

        BigDecimal active24hTotal = DeterministicReconciler.scale(
                approvalRepository.sumAmountInWindow(
                        command.orderId(),
                        now.minus(Duration.ofHours(24)),
                        VELOCITY_STATUSES
                )
        );

        boolean exceedsSingleLimit = command.amount()
                .compareTo(properties.mandate().autoApproveSingleLimit()) > 0;
        boolean exceedsRolling24hLimit = active24hTotal.add(command.amount())
                .compareTo(properties.mandate().rolling24hOrderLimit()) > 0;

        String approvalId = generateApprovalId();
        Instant expiresAt = now.plus(Duration.ofHours(properties.mandate().approvalTtlHours()));

        if (exceedsSingleLimit || exceedsRolling24hLimit) {
            String internalRule = exceedsSingleLimit ? RULE_SINGLE_LIMIT : RULE_ROLLING_24H_VELOCITY;
            approvalRepository.save(newApprovalOrder(
                    approvalId, command, snapshotHash, ApprovalStatus.PENDING_APPROVAL, internalRule, now, expiresAt));

            RefundDecision holdDecision = new RefundDecision(
                    ApprovalStatus.PENDING_APPROVAL.name(),
                    approvalId,
                    null,
                    command.orderId(),
                    command.amount(),
                    command.currency(),
                    OPAQUE_HOLD_MESSAGE
            );
            return new LockedEvaluation(holdDecision, false, false, internalRule, null);
        }

        approvalRepository.save(newApprovalOrder(
                approvalId, command, snapshotHash, ApprovalStatus.EXECUTING, RULE_AUTO_UNDER_MANDATE, now, expiresAt));

        RefundDecision reservedDecision = new RefundDecision(
                ApprovalStatus.EXECUTING.name(),
                approvalId,
                null,
                command.orderId(),
                command.amount(),
                command.currency(),
                "Reserved for automatic execution"
        );
        return new LockedEvaluation(reservedDecision, true, false, RULE_AUTO_UNDER_MANDATE, "SYSTEM_AUTO_MANDATE");
    }

    /**
     * Calls the payment gateway for a request already reserved as EXECUTING. Must be invoked outside any
     * database transaction. On success the request and the invoice ledger are settled atomically; on any
     * gateway or ledger error the request is frozen as PAYOUT_UNKNOWN and is never retried automatically.
     */
    private DispatchResult dispatchReservedPayout(
            String approvalId,
            String orderId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String webhookReviewerId) {
        PaymentGatewayPort.PaymentExecutionResult result;
        try {
            result = paymentGateway.executeRefund(orderId, amount, currency, idempotencyKey);
        } catch (RuntimeException gatewayFailure) {
            freezeUnknown(approvalId);
            throw new GuardrailExceptions.PayoutOutcomeUnknownException(
                    "Payout outcome unknown for " + approvalId + "; frozen for manual reconciliation",
                    gatewayFailure);
        }

        try {
            Long outboxId = requiresNewTx.execute(status -> {
                int settled = approvalRepository.completeExecution(approvalId, result.transactionId());
                if (settled != 1) {
                    throw new IllegalStateException("Reserved payout " + approvalId + " was not in an executable state");
                }
                int updated = invoiceRepository.incrementRefundedAmount(orderId, amount);
                if (updated != 1) {
                    throw new IllegalStateException("Ledger rejected payout " + approvalId);
                }
                if (!StringUtils.hasText(webhookReviewerId)) {
                    return null;
                }
                return webhookNotifier.stage(new ApprovalWebhookNotifier.ApprovalDecisionEvent(
                        approvalId,
                        orderId,
                        amount,
                        currency,
                        ApprovalStatus.EXECUTED,
                        result.transactionId(),
                        webhookReviewerId,
                        Instant.now()));
            });
            return new DispatchResult(result.transactionId(), outboxId);
        } catch (RuntimeException ledgerFailure) {
            freezeUnknown(approvalId);
            throw new GuardrailExceptions.PayoutOutcomeUnknownException(
                    "Payout outcome unknown for " + approvalId + "; frozen for manual reconciliation",
                    ledgerFailure);
        }
    }

    /** The approval created under this key, or the in-flight approval this key was linked to. */
    private Optional<RefundApprovalOrder> ownedOrLinkedApproval(String idempotencyKey) {
        Optional<RefundApprovalOrder> owned = approvalRepository.findByIdempotencyKey(idempotencyKey);
        if (owned.isPresent()) {
            return owned;
        }
        return idempotencyKeyStore.linkedApprovalId(idempotencyKey).flatMap(approvalRepository::findByApprovalId);
    }

    private void expireBeforeClaim(String approvalId) {
        expireIfOverdue(findOrThrow(approvalId), Instant.now());
    }

    /** In-transaction guard for the rare case where the deadline passes between the pre-check and the claim. */
    private static void rejectIfOverdue(RefundApprovalOrder order, Instant now) {
        if (order.getStatus() == ApprovalStatus.PENDING_APPROVAL && !order.getExpiresAt().isAfter(now)) {
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Approval request has expired and must be re-submitted: " + order.getApprovalId());
        }
    }

    private void closeResolution(long resolutionId, String reviewer, Instant now) {
        int closed = resolutionRepository.closeProposal(resolutionId, PayoutResolution.Status.APPLIED, reviewer, now);
        if (closed != 1) {
            throw new GuardrailExceptions.ApprovalStateConflictException("Payout resolution is no longer awaiting review");
        }
    }

    private void freezeUnknown(String approvalId) {
        requiresNewTx.executeWithoutResult(status -> approvalRepository.markPayoutUnknown(approvalId));
    }

    private void lockAnchor(String orderId) {
        anchorRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Missing order lock anchor for " + orderId));
    }

    private RefundApprovalOrder findOrThrow(String approvalId) {
        return approvalRepository.findByApprovalId(approvalId)
                .orElseThrow(() -> new GuardrailExceptions.ApprovalNotFoundException(
                        "Approval request not found: " + approvalId));
    }

    private void expireIfOverdue(RefundApprovalOrder order, Instant now) {
        if (order.getStatus() == ApprovalStatus.PENDING_APPROVAL && !order.getExpiresAt().isAfter(now)) {
            requiresNewTx.executeWithoutResult(
                    s -> approvalRepository.expireOverdueForOrder(order.getOrderId(), now));
            RefundApprovalOrder expired = requiresNewTx.execute(s -> findOrThrow(order.getApprovalId()));
            if (expired != null) {
                idempotencyKeyStore.recordTerminalDecision(
                        order.getIdempotencyKey(),
                        toDecision(expired, EXPIRED_MESSAGE));
            }
            throw new GuardrailExceptions.ApprovalStateConflictException(
                    "Approval request has expired and must be re-submitted: " + order.getApprovalId());
        }
    }

    private void rememberDecision(String idempotencyKey, RefundDecision decision) {
        if (!idempotencyKeyStore.markCompleted(idempotencyKey, decision)) {
            idempotencyKeyStore.recordTerminalDecision(idempotencyKey, decision);
        }
    }

    private void rememberQuietly(String idempotencyKey, RefundDecision decision) {
        try {
            rememberDecision(idempotencyKey, decision);
        } catch (RuntimeException ex) {
            log.warn("Could not store the refund decision on idempotency key {}", idempotencyKey, ex);
        }
    }

    private void safeAudit(
            String operationName,
            String orderId,
            String approvalId,
            BigDecimal amount,
            String decisionStatus,
            String internalPolicyRule,
            String reviewerId,
            long startNanos) {
        try {
            auditLog.record(
                    operationName,
                    orderId,
                    approvalId,
                    amount,
                    decisionStatus,
                    internalPolicyRule,
                    reviewerId,
                    elapsedMs(startNanos));
        } catch (RuntimeException ex) {
            log.warn("Audit log write failed after the settlement decision was committed: operation={}, approvalId={}",
                    operationName, approvalId, ex);
        }
    }

    private static boolean isRefreshableCachedStatus(String status) {
        return ApprovalStatus.PENDING_APPROVAL.name().equals(status)
                || ApprovalStatus.EXECUTING.name().equals(status)
                || ApprovalStatus.PAYOUT_UNKNOWN.name().equals(status);
    }

    static String requireReviewer(String reviewerId) {
        if (!StringUtils.hasText(reviewerId)) {
            throw new GuardrailExceptions.InvalidSettlementRequestException("Missing reviewer identity");
        }
        return reviewerId.trim();
    }

    static String requireTransactionId(String transactionId) {
        if (!StringUtils.hasText(transactionId) || !TRANSACTION_ID.matcher(transactionId.trim()).matches()) {
            throw new GuardrailExceptions.InvalidSettlementRequestException(
                    "transactionId must be 1-64 characters of letters, digits, '.', '_', ':' or '-'");
        }
        return transactionId.trim();
    }

    private RefundCommand validateAndNormalize(RefundCommand raw) {
        if (raw == null) {
            throw new GuardrailExceptions.InvalidSettlementRequestException("Refund request must not be null");
        }
        if (!StringUtils.hasText(raw.orderId())) {
            throw new GuardrailExceptions.InvalidSettlementRequestException("orderId is required");
        }
        if (!StringUtils.hasText(raw.idempotencyKey())) {
            throw new GuardrailExceptions.InvalidSettlementRequestException("idempotencyKey is required");
        }
        if (raw.amount() == null || raw.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new GuardrailExceptions.InvalidSettlementRequestException("amount must be positive");
        }
        if (raw.amount().stripTrailingZeros().scale() > 2) {
            throw new GuardrailExceptions.InvalidSettlementRequestException("amount must have at most 2 fraction digits");
        }
        String normalizedCurrency = StringUtils.hasText(raw.currency())
                ? raw.currency().trim().toUpperCase(Locale.ROOT)
                : properties.mandate().currency();
        if (!properties.mandate().currency().equalsIgnoreCase(normalizedCurrency)) {
            throw new GuardrailExceptions.InvalidSettlementRequestException(
                    "Unsupported settlement currency: " + normalizedCurrency);
        }
        String normalizedReason = StringUtils.hasText(raw.reasonCode())
                ? raw.reasonCode().trim().toUpperCase(Locale.ROOT)
                : "OVERCHARGE_ADJUSTMENT";

        return new RefundCommand(
                raw.orderId().trim(),
                DeterministicReconciler.scale(raw.amount()),
                normalizedCurrency,
                normalizedReason,
                raw.idempotencyKey().trim()
        );
    }

    private static RefundApprovalOrder newApprovalOrder(
            String approvalId,
            RefundCommand command,
            String snapshotHash,
            ApprovalStatus status,
            String internalRule,
            Instant now,
            Instant expiresAt) {
        return new RefundApprovalOrder(
                approvalId,
                command.orderId(),
                command.amount(),
                command.currency(),
                command.reasonCode(),
                command.idempotencyKey(),
                snapshotHash,
                status,
                internalRule,
                null,
                now,
                expiresAt
        );
    }

    private void verifySnapshotIntegrity(RefundApprovalOrder order) {
        String recomputed = snapshotHasher.hash(
                order.getOrderId(),
                DeterministicReconciler.scale(order.getAmount()),
                order.getCurrency(),
                order.getReasonCode()
        );
        if (!recomputed.equals(order.getSnapshotHash())) {
            throw new GuardrailExceptions.TamperedSnapshotException(
                    "Stored approval snapshot failed integrity verification for " + order.getApprovalId());
        }
    }

    /** Idempotency fingerprint: every business field of the request, so any change under the same key is a conflict. */
    public static String computeRequestFingerprint(RefundCommand command) {
        String canonical = command.orderId()
                + "|" + command.amount().toPlainString()
                + "|" + command.currency()
                + "|" + command.reasonCode();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String generateApprovalId() {
        return "apr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String messageFor(ApprovalStatus status) {
        return switch (status) {
            case EXECUTED -> "Refund is already settled";
            case PAYOUT_UNKNOWN -> FROZEN_PAYOUT_MESSAGE;
            case PAYOUT_RELEASED -> "Payout reservation was released after finance confirmed no money moved";
            case REJECTED -> "Rejected by finance reviewer; in-flight allowance released";
            case EXPIRED -> EXPIRED_MESSAGE;
            case PENDING_APPROVAL -> OPAQUE_HOLD_MESSAGE;
            case EXECUTING -> REUSE_KEY_MESSAGE;
        };
    }

    private static String unknownDiagnostic(String approvalId) {
        if (!StringUtils.hasText(approvalId)) {
            return "{\"status\":\"PAYOUT_UNKNOWN\",\"reason\":\"Execution interrupted; manual reconciliation required\"}";
        }
        return "{\"status\":\"PAYOUT_UNKNOWN\",\"approvalId\":\"" + approvalId
                + "\",\"reason\":\"Execution interrupted; manual reconciliation required\"}";
    }

    private static RefundDecision toDecision(RefundApprovalOrder order, String message) {
        return new RefundDecision(
                order.getStatus().name(),
                order.getApprovalId(),
                order.getTransactionId(),
                order.getOrderId(),
                order.getAmount(),
                order.getCurrency(),
                message
        );
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private record LockedEvaluation(
            RefundDecision decision,
            boolean payoutReserved,
            boolean linksExistingRequest,
            String internalRuleCode,
            String reviewerMarker
    ) {
    }

    private record ApprovalClaim(
            RefundApprovalOrder order,
            boolean claimedByThisCall,
            Long outboxId
    ) {
    }

    private record Resolved(
            RefundDecision decision,
            RefundApprovalOrder order,
            boolean changed,
            Long outboxId
    ) {
    }

    private record DispatchResult(String transactionId, Long outboxId) {
    }

    private record LoadedStatus(
            ApprovalStatusView view,
            String idempotencyKey
    ) {
    }

    public record RefundCommand(
            String orderId,
            BigDecimal amount,
            String currency,
            String reasonCode,
            String idempotencyKey
    ) {
    }

    public record RefundDecision(
            String status,
            String approvalId,
            String refundTxId,
            String orderId,
            BigDecimal amount,
            String currency,
            String message
    ) {
    }

    public record ApprovalStatusView(
            String approvalId,
            String orderId,
            BigDecimal amount,
            String currency,
            String reasonCode,
            String internalRuleCode,
            String status,
            String refundTxId,
            String reviewerId,
            Instant expiresAt,
            BigDecimal rolling24hUsed,
            BigDecimal refundableAmount
    ) {
    }
}
