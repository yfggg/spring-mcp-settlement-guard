package io.mcpguard.settlement.guardrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "approval_event_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_outbox_unpublished", columnList = "published_at,created_at")
)
public class ApprovalEventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable across redeliveries, so consumers can drop duplicates. */
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "approval_id", nullable = false, length = 64)
    private String approvalId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "reviewer_id", length = 128)
    private String reviewerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected ApprovalEventOutbox() {
    }

    public ApprovalEventOutbox(ApprovalWebhookNotifier.ApprovalDecisionEvent event, Instant createdAt) {
        this.eventId = event.eventId() != null ? event.eventId() : UUID.randomUUID().toString();
        this.approvalId = event.approvalId();
        this.orderId = event.orderId();
        this.amount = event.amount();
        this.currency = event.currency();
        this.status = event.status().name();
        this.transactionId = event.refundTxId();
        this.reviewerId = event.reviewerId();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public ApprovalWebhookNotifier.ApprovalDecisionEvent toEvent() {
        return new ApprovalWebhookNotifier.ApprovalDecisionEvent(
                eventId,
                approvalId,
                orderId,
                amount,
                currency,
                ApprovalStatus.valueOf(status),
                transactionId,
                reviewerId,
                createdAt
        );
    }
}
