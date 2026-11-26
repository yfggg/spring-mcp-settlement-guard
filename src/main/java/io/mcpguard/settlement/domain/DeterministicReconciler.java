package io.mcpguard.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pure BigDecimal settlement reconciliation engine.
 * Strictly read-only: holds zero payment gateway or ledger mutation dependencies,
 * eliminating LLM arithmetic hallucinations and accidental ledger writes.
 */
@Service
public class DeterministicReconciler {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final SettlementInvoiceRepository invoiceRepository;

    public DeterministicReconciler(SettlementInvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional(readOnly = true)
    public BatchReconciliationReport reconcileBatch(String batchId) {
        List<SettlementInvoiceItem> items = invoiceRepository.findByBatchIdOrderByOrderIdAsc(batchId);
        List<OrderDiscrepancyLine> discrepancies = new ArrayList<>();

        BigDecimal totalExpected = scale(BigDecimal.ZERO);
        BigDecimal totalNetCaptured = scale(BigDecimal.ZERO);
        BigDecimal totalOvercharge = scale(BigDecimal.ZERO);

        for (SettlementInvoiceItem item : items) {
            BigDecimal expected = scale(item.getExpectedAmount());
            BigDecimal captured = scale(item.getCapturedAmount());
            BigDecimal refunded = scale(item.getAlreadyRefundedAmount());
            BigDecimal netCaptured = scale(captured.subtract(refunded));
            BigDecimal variance = scale(netCaptured.subtract(expected));

            totalExpected = scale(totalExpected.add(expected));
            totalNetCaptured = scale(totalNetCaptured.add(netCaptured));

            if (variance.compareTo(BigDecimal.ZERO) > 0) {
                totalOvercharge = scale(totalOvercharge.add(variance));
                discrepancies.add(new OrderDiscrepancyLine(
                        item.getOrderId(),
                        item.getCurrency(),
                        expected,
                        netCaptured,
                        variance,
                        "OVERCHARGED"
                ));
            } else if (variance.compareTo(BigDecimal.ZERO) < 0) {
                discrepancies.add(new OrderDiscrepancyLine(
                        item.getOrderId(),
                        item.getCurrency(),
                        expected,
                        netCaptured,
                        variance.abs(),
                        "UNDERCHARGED"
                ));
            }
        }

        return new BatchReconciliationReport(
                batchId,
                items.size(),
                discrepancies.size(),
                totalExpected,
                totalNetCaptured,
                totalOvercharge,
                discrepancies
        );
    }

    public static BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        }
        return value.setScale(SCALE, ROUNDING);
    }

    public record OrderDiscrepancyLine(
            String orderId,
            String currency,
            BigDecimal expectedAmount,
            BigDecimal netCapturedAmount,
            BigDecimal discrepancyAmount,
            String status
    ) {
    }

    public record BatchReconciliationReport(
            String batchId,
            int inspectedOrders,
            int discrepancyCount,
            BigDecimal totalExpectedAmount,
            BigDecimal totalNetCapturedAmount,
            BigDecimal totalRefundableOvercharge,
            List<OrderDiscrepancyLine> discrepancies
    ) {
    }
}
