package io.mcpguard.settlement.guardrail;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM IdempotencyRecord r
             WHERE r.idempotencyKey = :idempotencyKey
               AND r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.IN_PROGRESS
            """)
    int deleteInProgress(@Param("idempotencyKey") String idempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM IdempotencyRecord r
             WHERE r.idempotencyKey = :idempotencyKey
               AND r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.UNKNOWN_SUSPENDED
            """)
    int deleteSuspended(@Param("idempotencyKey") String idempotencyKey);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE IdempotencyRecord r
               SET r.linkedApprovalId = :approvalId
             WHERE r.idempotencyKey = :idempotencyKey
            """)
    int linkApproval(@Param("idempotencyKey") String idempotencyKey, @Param("approvalId") String approvalId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IdempotencyRecord r
               SET r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.COMPLETED,
                   r.responseJson = :responseJson,
                   r.updatedAt = :now
             WHERE r.idempotencyKey = :idempotencyKey
               AND r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.IN_PROGRESS
            """)
    int completeIfInProgress(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("responseJson") String responseJson,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IdempotencyRecord r
               SET r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.UNKNOWN_SUSPENDED,
                   r.responseJson = :diagnosticJson,
                   r.updatedAt = :now
             WHERE r.idempotencyKey = :idempotencyKey
               AND r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.IN_PROGRESS
            """)
    int suspendIfInProgress(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("diagnosticJson") String diagnosticJson,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IdempotencyRecord r
               SET r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.UNKNOWN_SUSPENDED,
                   r.responseJson = :diagnosticJson,
                   r.updatedAt = :now
             WHERE r.state = io.mcpguard.settlement.guardrail.IdempotencyRecord.State.IN_PROGRESS
               AND r.updatedAt < :cutoff
            """)
    int suspendStaleInProgress(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("diagnosticJson") String diagnosticJson);
}
