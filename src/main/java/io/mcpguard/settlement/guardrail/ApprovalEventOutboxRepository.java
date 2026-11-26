package io.mcpguard.settlement.guardrail;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalEventOutboxRepository extends JpaRepository<ApprovalEventOutbox, Long> {

    List<ApprovalEventOutbox> findByPublishedAtIsNullOrderByIdAsc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ApprovalEventOutbox e
               SET e.publishedAt = :publishedAt
             WHERE e.id = :id
               AND e.publishedAt IS NULL
            """)
    int markPublished(@Param("id") Long id, @Param("publishedAt") Instant publishedAt);
}
