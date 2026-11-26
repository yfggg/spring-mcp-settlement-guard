package io.mcpguard.settlement.guardrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "payout_reconciliation_exception",
        uniqueConstraints = @UniqueConstraint(name = "uk_recon_open_approval", columnNames = "open_approval_id"),
        indexes = @Index(name = "idx_recon_approval", columnList = "approval_id")
)
public class PayoutReconciliationException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", nullable = false, length = 64)
    private String approvalId;

    @Column(name = "open_approval_id", length = 64)
    private String openApprovalId;

    @Column(name = "mismatch_code", nullable = false, length = 64)
    private String mismatchCode;

    @Column(name = "provider_state", nullable = false, length = 32)
    private String providerState;

    @Column(name = "detail", nullable = false, length = 512)
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected PayoutReconciliationException() {
    }

    public PayoutReconciliationException(
            String approvalId, String mismatchCode, String providerState, String detail, Instant detectedAt) {
        this.approvalId = approvalId;
        this.openApprovalId = approvalId;
        this.mismatchCode = mismatchCode;
        this.providerState = providerState;
        this.detail = detail.length() > 512 ? detail.substring(0, 512) : detail;
        this.detectedAt = detectedAt;
    }

    public Long getId() {
        return id;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getMismatchCode() {
        return mismatchCode;
    }

    public String getProviderState() {
        return providerState;
    }

    public String getDetail() {
        return detail;
    }
}
