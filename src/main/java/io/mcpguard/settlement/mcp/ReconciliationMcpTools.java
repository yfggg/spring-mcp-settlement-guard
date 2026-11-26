package io.mcpguard.settlement.mcp;

import io.mcpguard.settlement.domain.DeterministicReconciler;
import io.mcpguard.settlement.telemetry.ToolExecutionAuditLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * Read-only deterministic MCP tool exposing exact BigDecimal batch reconciliation
 * without allowing LLMs to perform floating-point or mental arithmetic.
 */
@Service
@Validated
public class ReconciliationMcpTools {

    private final DeterministicReconciler reconciler;
    private final ToolExecutionAuditLog auditLog;

    public ReconciliationMcpTools(DeterministicReconciler reconciler, ToolExecutionAuditLog auditLog) {
        this.reconciler = reconciler;
        this.auditLog = auditLog;
    }

    @Tool(
            name = "reconcile_settlement_batch",
            description = "Deterministically reconcile settlement batch invoices against captured gateway amounts using exact Java BigDecimal arithmetic. Read-only."
    )
    public DeterministicReconciler.BatchReconciliationReport reconcileSettlementBatch(
            @ToolParam(description = "Settlement batch identifier (letters, digits, '-' or '_'), e.g. BATCH-2026-11")
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$", message = "must contain only letters, digits, '-' or '_'")
            String batchId) {
        long startNanos = System.nanoTime();
        DeterministicReconciler.BatchReconciliationReport report = reconciler.reconcileBatch(batchId);
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        auditLog.recordBatchReconciliation(batchId, report.totalRefundableOvercharge(), durationMs);
        return report;
    }
}
