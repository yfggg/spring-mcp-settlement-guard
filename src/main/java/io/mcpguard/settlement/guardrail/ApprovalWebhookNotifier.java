package io.mcpguard.settlement.guardrail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two-step approval events. {@link #stage} joins the caller's transaction so the outbox row
 * commits together with the status change. {@link #publishStaged} runs only after that commit;
 * a crash in between leaves an unpublished row for the sweeper.
 */
@Component
public class ApprovalWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWebhookNotifier.class);

    private final ApprovalEventOutboxRepository outboxRepository;
    private final TransactionTemplate requiresNewTx;
    private final CopyOnWriteArrayList<ApprovalDecisionEvent> publishedEvents = new CopyOnWriteArrayList<>();

    public ApprovalWebhookNotifier(
            ApprovalEventOutboxRepository outboxRepository,
            PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Inserts the outbox row in the transaction already in progress. Call this beside the status update,
     * then {@link #publishStaged} after that transaction has committed.
     */
    public Long stage(ApprovalDecisionEvent event) {
        return outboxRepository.saveAndFlush(new ApprovalEventOutbox(event, Instant.now())).getId();
    }

    /** Delivers one staged row. Failures stay unpublished so the sweeper can retry. */
    public void publishStaged(Long id) {
        if (id == null) {
            return;
        }
        try {
            ApprovalEventOutbox row = outboxRepository.findById(id).orElse(null);
            if (row == null || row.getPublishedAt() != null) {
                return;
            }
            deliver(id, row.toEvent());
        } catch (RuntimeException ex) {
            log.warn("Approval event {} is staged and will be retried by the sweeper", id, ex);
        }
    }

    /** Delivers rows left unpublished by a crash between commit and the listener call. */
    public int republishUnpublished() {
        List<ApprovalEventOutbox> pending = outboxRepository.findByPublishedAtIsNullOrderByIdAsc();
        int delivered = 0;
        for (ApprovalEventOutbox row : pending) {
            deliver(row.getId(), row.toEvent());
            delivered++;
        }
        return delivered;
    }

    public List<ApprovalDecisionEvent> getPublishedEvents() {
        return List.copyOf(publishedEvents);
    }

    public void clearEvents() {
        publishedEvents.clear();
    }

    private void deliver(Long id, ApprovalDecisionEvent event) {
        publishedEvents.add(event);
        log.info("Published settlement approval callback: approvalId={}, orderId={}, status={}, txId={}, reviewer={}",
                event.approvalId(), event.orderId(), event.status(), event.refundTxId(), event.reviewerId());
        if (id != null) {
            requiresNewTx.executeWithoutResult(status -> outboxRepository.markPublished(id, Instant.now()));
        }
    }

    public record ApprovalDecisionEvent(
            String approvalId,
            String orderId,
            BigDecimal amount,
            String currency,
            ApprovalStatus status,
            String refundTxId,
            String reviewerId,
            Instant decidedAt
    ) {
    }
}
