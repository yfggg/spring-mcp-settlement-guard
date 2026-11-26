package io.mcpguard.settlement.guardrail;

import io.mcpguard.settlement.config.GuardrailProperties;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Crash recovery for the two places a request can get stuck mid-flight:
 * an idempotency key left IN_PROGRESS, or a payout reservation left EXECUTING.
 * Neither is retried automatically; both are frozen so finance can reconcile them against the gateway.
 * Unpublished approval events are delivered again on the same pass.
 */
@Component
public class StaleExecutionSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleExecutionSweeper.class);

    private final GuardrailProperties properties;
    private final IdempotencyKeyStore idempotencyKeyStore;
    private final RefundApprovalOrderRepository approvalRepository;
    private final ApprovalWebhookNotifier webhookNotifier;
    private final TransactionTemplate txTemplate;

    public StaleExecutionSweeper(
            GuardrailProperties properties,
            IdempotencyKeyStore idempotencyKeyStore,
            RefundApprovalOrderRepository approvalRepository,
            ApprovalWebhookNotifier webhookNotifier,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.idempotencyKeyStore = idempotencyKeyStore;
        this.approvalRepository = approvalRepository;
        this.webhookNotifier = webhookNotifier;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            initialDelayString = "${guardrail.recovery.sweep-interval-ms}",
            fixedDelayString = "${guardrail.recovery.sweep-interval-ms}")
    public void scheduledSweep() {
        sweep(Instant.now());
    }

    public SweepResult sweep(Instant now) {
        Instant cutoff = now.minus(Duration.ofMinutes(properties.recovery().staleAfterMinutes()));
        int suspendedKeys = idempotencyKeyStore.suspendStaleInProgress(cutoff, now);
        Integer frozenPayouts = txTemplate.execute(status -> approvalRepository.markStaleExecutionsUnknown(cutoff));
        SweepResult result = new SweepResult(suspendedKeys, frozenPayouts == null ? 0 : frozenPayouts);
        int redelivered = webhookNotifier.republishUnpublished(
                now.minus(Duration.ofSeconds(properties.recovery().outboxGraceSeconds())));
        if (result.suspendedIdempotencyKeys() > 0 || result.frozenPayouts() > 0) {
            log.warn("Froze stale in-flight requests for manual reconciliation: idempotencyKeys={}, payouts={}",
                    result.suspendedIdempotencyKeys(), result.frozenPayouts());
        }
        if (redelivered > 0) {
            log.warn("Redelivered {} unpublished approval event(s)", redelivered);
        }
        return result;
    }

    public record SweepResult(int suspendedIdempotencyKeys, int frozenPayouts) {
    }
}
