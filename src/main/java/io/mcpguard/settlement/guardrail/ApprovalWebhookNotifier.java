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
 *
 * <p>Delivery is at least once: the sweeper can redeliver a row whose delivery succeeded but whose
 * {@code published_at} update did not, and several nodes can sweep at the same time. Every event carries
 * a stable {@code eventId} so consumers can drop duplicates.
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

    /** Delivers rows created before {@code cutoff} that are still unpublished. One failure does not stop the rest. */
    public int republishUnpublished(Instant cutoff) {
        List<ApprovalEventOutbox> pending = outboxRepository.findByPublishedAtIsNullAndCreatedAtBeforeOrderByIdAsc(cutoff);
        int delivered = 0;
        for (ApprovalEventOutbox row : pending) {
            try {
                deliver(row.getId(), row.toEvent());
                delivered++;
            } catch (RuntimeException ex) {
                log.warn("Redelivery of approval event {} failed; it stays queued", row.getEventId(), ex);
            }
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
        log.info("Published settlement approval callback: eventId={}, approvalId={}, orderId={}, status={}, txId={}, reviewer={}",
                event.eventId(), event.approvalId(), event.orderId(), event.status(), event.refundTxId(), event.reviewerId());
        if (id != null) {
            requiresNewTx.executeWithoutResult(status -> outboxRepository.markPublished(id, Instant.now()));
        }
    }

    public record ApprovalDecisionEvent(
            String eventId,
            String approvalId,
            String orderId,
            BigDecimal amount,
            String currency,
            ApprovalStatus status,
            String refundTxId,
            String reviewerId,
            Instant decidedAt
    ) {
        /** New event; the outbox assigns the event id when the row is staged. */
        public ApprovalDecisionEvent(
                String approvalId,
                String orderId,
                BigDecimal amount,
                String currency,
                ApprovalStatus status,
                String refundTxId,
                String reviewerId,
                Instant decidedAt) {
            this(null, approvalId, orderId, amount, currency, status, refundTxId, reviewerId, decidedAt);
        }
    }
}
