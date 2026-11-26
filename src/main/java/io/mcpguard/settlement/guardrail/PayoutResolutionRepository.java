package io.mcpguard.settlement.guardrail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutResolutionRepository extends JpaRepository<PayoutResolution, Long> {

    Optional<PayoutResolution> findByOpenApprovalId(String approvalId);

    List<PayoutResolution> findByApprovalIdOrderByIdAsc(String approvalId);

    /** CAS: closes a proposal only while it is still open. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PayoutResolution r
               SET r.status = :status,
                   r.openApprovalId = NULL,
                   r.decidedBy = :decidedBy,
                   r.decidedAt = :decidedAt
             WHERE r.id = :id
               AND r.status = io.mcpguard.settlement.guardrail.PayoutResolution.Status.PROPOSED
            """)
    int closeProposal(
            @Param("id") long id,
            @Param("status") PayoutResolution.Status status,
            @Param("decidedBy") String decidedBy,
            @Param("decidedAt") Instant decidedAt);
}
