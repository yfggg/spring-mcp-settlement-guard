package io.mcpguard.settlement.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "tool_execution_audit_log",
        indexes = {
                @Index(name = "idx_audit_order_id", columnList = "order_id"),
                @Index(name = "idx_audit_approval_id", columnList = "approval_id"),
                @Index(name = "idx_audit_batch_id", columnList = "batch_id")
        }
)
public class AuditLogRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_name", nullable = false, length = 80)
    private String operationName;

    @Column(name = "caller_principal", nullable = false, length = 128)
    private String callerPrincipal;

    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "approval_id", length = 64)
    private String approvalId;

    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "decision_status", nullable = false, length = 48)
    private String decisionStatus;

    @Column(name = "internal_policy_rule", nullable = false, length = 80)
    private String internalPolicyRule;

    @Column(name = "reviewer_id", length = 128)
    private String reviewerId;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "llm_tokens_burned", nullable = false)
    private int llmTokensBurned;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogRecord() {
    }

    public AuditLogRecord(
            String operationName,
            String callerPrincipal,
            String batchId,
            String orderId,
            String approvalId,
            BigDecimal amount,
            String decisionStatus,
            String internalPolicyRule,
            String reviewerId,
            long durationMs,
            int llmTokensBurned,
            Instant createdAt) {
        this.operationName = operationName;
        this.callerPrincipal = callerPrincipal;
        this.batchId = batchId;
        this.orderId = orderId;
        this.approvalId = approvalId;
        this.amount = amount;
        this.decisionStatus = decisionStatus;
        this.internalPolicyRule = internalPolicyRule;
        this.reviewerId = reviewerId;
        this.durationMs = durationMs;
        this.llmTokensBurned = llmTokensBurned;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getCallerPrincipal() {
        return callerPrincipal;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDecisionStatus() {
        return decisionStatus;
    }

    public String getInternalPolicyRule() {
        return internalPolicyRule;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getLlmTokensBurned() {
        return llmTokensBurned;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
