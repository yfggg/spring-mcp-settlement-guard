package io.mcpguard.settlement.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable audit trail recording caller identity, execution latency, internal policy rule triggered,
 * human reviewer sign-off, and deterministic 0-token server execution metrics.
 */
@Service
public class ToolExecutionAuditLog {

    private final AuditLogRepository auditLogRepository;

    public ToolExecutionAuditLog(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String operationName,
            String orderId,
            String approvalId,
            BigDecimal amount,
            String decisionStatus,
            String internalPolicyRule,
            String reviewerId,
            long durationMs) {
        save(operationName, null, orderId, approvalId, amount, decisionStatus, internalPolicyRule, reviewerId, durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBatchReconciliation(String batchId, BigDecimal totalOvercharge, long durationMs) {
        save("reconcile_settlement_batch", batchId, null, null, totalOvercharge,
                "RECONCILED_READ_ONLY", "READ_ONLY_DETERMINISTIC_MATH", null, durationMs);
    }

    @Transactional(readOnly = true)
    public List<AuditLogRecord> findByOrderId(String orderId) {
        return auditLogRepository.findByOrderIdOrderByIdAsc(orderId);
    }

    @Transactional(readOnly = true)
    public List<AuditLogRecord> findByBatchId(String batchId) {
        return auditLogRepository.findByBatchIdOrderByIdAsc(batchId);
    }

    private void save(
            String operationName,
            String batchId,
            String orderId,
            String approvalId,
            BigDecimal amount,
            String decisionStatus,
            String internalPolicyRule,
            String reviewerId,
            long durationMs) {
        auditLogRepository.save(new AuditLogRecord(
                operationName,
                resolveCallerPrincipal(),
                batchId,
                orderId,
                approvalId,
                amount,
                decisionStatus,
                internalPolicyRule,
                reviewerId,
                Math.max(0L, durationMs),
                0,
                Instant.now()
        ));
    }

    private static String resolveCallerPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "internal-system";
        }
        return auth.getName();
    }
}
