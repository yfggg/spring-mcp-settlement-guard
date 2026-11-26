package io.mcpguard.settlement.guardrail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundApprovalOrderRepository extends JpaRepository<RefundApprovalOrder, Long> {

    Optional<RefundApprovalOrder> findByApprovalId(String approvalId);

    Optional<RefundApprovalOrder> findByIdempotencyKey(String idempotencyKey);

    boolean existsByTransactionId(String transactionId);

    /** Same order, amount, currency, and reason, still reserved (pending, executing, or frozen). Oldest first. */
    @Query("""
            SELECT r FROM RefundApprovalOrder r
             WHERE r.orderId = :orderId
               AND r.amount = :amount
               AND r.currency = :currency
               AND r.reasonCode = :reasonCode
               AND r.status IN :statuses
             ORDER BY r.createdAt ASC
            """)
    List<RefundApprovalOrder> findInFlightDuplicates(
            @Param("orderId") String orderId,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("reasonCode") String reasonCode,
            @Param("statuses") Collection<ApprovalStatus> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.EXPIRED
             WHERE r.orderId = :orderId
               AND r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PENDING_APPROVAL
               AND r.expiresAt <= :now
            """)
    int expireOverdueForOrder(@Param("orderId") String orderId, @Param("now") Instant now);

    @Query("""
            SELECT COALESCE(SUM(r.amount), 0)
              FROM RefundApprovalOrder r
             WHERE r.orderId = :orderId
               AND r.createdAt >= :windowStart
               AND r.status IN :statuses
            """)
    BigDecimal sumAmountInWindow(
            @Param("orderId") String orderId,
            @Param("windowStart") Instant windowStart,
            @Param("statuses") Collection<ApprovalStatus> statuses);

    @Query("""
            SELECT COALESCE(SUM(r.amount), 0)
              FROM RefundApprovalOrder r
             WHERE r.orderId = :orderId
               AND r.status IN :statuses
            """)
    BigDecimal sumAmountByStatuses(
            @Param("orderId") String orderId,
            @Param("statuses") Collection<ApprovalStatus> statuses);

    /** CAS: only one reviewer can move a live pending request into EXECUTING. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.EXECUTING,
                   r.reviewerId = :reviewerId,
                   r.reviewedAt = :now
             WHERE r.approvalId = :approvalId
               AND r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PENDING_APPROVAL
               AND r.expiresAt > :now
            """)
    int claimPendingForExecution(
            @Param("approvalId") String approvalId,
            @Param("reviewerId") String reviewerId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.REJECTED,
                   r.reviewerId = :reviewerId,
                   r.reviewedAt = :now
             WHERE r.approvalId = :approvalId
               AND r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PENDING_APPROVAL
               AND r.expiresAt > :now
            """)
    int claimPendingForRejection(
            @Param("approvalId") String approvalId,
            @Param("reviewerId") String reviewerId,
            @Param("now") Instant now);

    /** Records a confirmed payout. PAYOUT_UNKNOWN is accepted so a late gateway confirmation still settles. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.EXECUTED,
                   r.transactionId = :transactionId
             WHERE r.approvalId = :approvalId
               AND r.status IN (io.mcpguard.settlement.guardrail.ApprovalStatus.EXECUTING,
                                io.mcpguard.settlement.guardrail.ApprovalStatus.PAYOUT_UNKNOWN)
            """)
    int completeExecution(
            @Param("approvalId") String approvalId,
            @Param("transactionId") String transactionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PAYOUT_UNKNOWN
             WHERE r.approvalId = :approvalId
               AND r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.EXECUTING
            """)
    int markPayoutUnknown(@Param("approvalId") String approvalId);

    /** Finance confirmed the gateway moved money for a frozen payout. Does not call the gateway again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.EXECUTED,
                   r.transactionId = :transactionId,
                   r.reviewerId = :reviewerId,
                   r.reviewedAt = :now
             WHERE r.approvalId = :approvalId
               AND r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PAYOUT_UNKNOWN
            """)
    int settleUnknownPayout(
            @Param("approvalId") String approvalId,
            @Param("transactionId") String transactionId,
            @Param("reviewerId") String reviewerId,
            @Param("now") Instant now);

    /** Finance confirmed the gateway did not move money, so the reservation can be released. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PAYOUT_RELEASED,
                   r.reviewerId = :reviewerId,
                   r.reviewedAt = :now
             WHERE r.approvalId = :approvalId
               AND r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PAYOUT_UNKNOWN
            """)
    int releaseUnknownPayout(
            @Param("approvalId") String approvalId,
            @Param("reviewerId") String reviewerId,
            @Param("now") Instant now);

    /** Freezes reservations whose payout never reported back (for example after a process crash). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefundApprovalOrder r
               SET r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.PAYOUT_UNKNOWN
             WHERE r.status = io.mcpguard.settlement.guardrail.ApprovalStatus.EXECUTING
               AND COALESCE(r.reviewedAt, r.createdAt) < :cutoff
            """)
    int markStaleExecutionsUnknown(@Param("cutoff") Instant cutoff);
}
