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
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A proposed resolution for a payout frozen as {@code PAYOUT_UNKNOWN}. One reviewer proposes CONFIRM or RELEASE;
 * a different reviewer approves or rejects it. {@code open_approval_id} is set only while the proposal is open,
 * and its unique constraint allows at most one open proposal per approval.
 */
@Entity
@Table(
        name = "payout_resolution",
        uniqueConstraints = @UniqueConstraint(name = "uk_resolution_open_approval", columnNames = "open_approval_id"),
        indexes = @Index(name = "idx_resolution_approval", columnList = "approval_id")
)
public class PayoutResolution {

    public enum Action {
        CONFIRM,
        RELEASE
    }

    public enum Status {
        PROPOSED,
        APPLIED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", nullable = false, length = 64)
    private String approvalId;

    @Column(name = "open_approval_id", length = 64)
    private String openApprovalId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action", nullable = false, length = 16)
    private Action action;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "proposed_by", nullable = false, length = 128)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected PayoutResolution() {
    }

    public static PayoutResolution propose(
            String approvalId, Action action, String transactionId, String proposedBy, Instant now) {
        PayoutResolution r = new PayoutResolution();
        r.approvalId = approvalId;
        r.openApprovalId = approvalId;
        r.action = action;
        r.transactionId = transactionId;
        r.status = Status.PROPOSED;
        r.proposedBy = proposedBy;
        r.proposedAt = now;
        return r;
    }

    public Long getId() {
        return id;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public Action getAction() {
        return action;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public Status getStatus() {
        return status;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
