package io.mcpguard.settlement.guardrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "refund_approval_order",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_approval_id", columnNames = "approval_id"),
                @UniqueConstraint(name = "uk_approval_idempotency_key", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_approval_transaction_id", columnNames = "transaction_id")
        },
        indexes = {
                @Index(name = "idx_approval_order_window", columnList = "order_id,status,created_at")
        }
)
public class RefundApprovalOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", nullable = false, length = 64)
    private String approvalId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private ApprovalStatus status;

    @Column(name = "internal_rule_code", nullable = false, length = 64)
    private String internalRuleCode;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "reviewer_id", length = 128)
    private String reviewerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected RefundApprovalOrder() {
    }

    public RefundApprovalOrder(
            String approvalId,
            String orderId,
            BigDecimal amount,
            String currency,
            String reasonCode,
            String idempotencyKey,
            String snapshotHash,
            ApprovalStatus status,
            String internalRuleCode,
            String transactionId,
            Instant createdAt,
            Instant expiresAt) {
        this.approvalId = approvalId;
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.reasonCode = reasonCode;
        this.idempotencyKey = idempotencyKey;
        this.snapshotHash = snapshotHash;
        this.status = status;
        this.internalRuleCode = internalRuleCode;
        this.transactionId = transactionId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getSnapshotHash() {
        return snapshotHash;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getInternalRuleCode() {
        return internalRuleCode;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
