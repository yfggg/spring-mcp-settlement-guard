package io.mcpguard.settlement.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementInvoiceRepository extends JpaRepository<SettlementInvoiceItem, Long> {

    List<SettlementInvoiceItem> findByBatchIdOrderByOrderIdAsc(String batchId);

    Optional<SettlementInvoiceItem> findByOrderId(String orderId);

    /**
     * Atomic in-database increment. The captured-amount predicate rolls the settlement back
     * when the ledger cannot absorb the payout.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SettlementInvoiceItem i
               SET i.alreadyRefundedAmount = i.alreadyRefundedAmount + :delta
             WHERE i.orderId = :orderId
               AND i.alreadyRefundedAmount + :delta <= i.capturedAmount
            """)
    int incrementRefundedAmount(@Param("orderId") String orderId, @Param("delta") BigDecimal delta);
}
