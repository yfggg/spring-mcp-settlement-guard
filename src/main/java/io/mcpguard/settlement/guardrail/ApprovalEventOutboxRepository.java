package io.mcpguard.settlement.guardrail;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalEventOutboxRepository extends JpaRepository<ApprovalEventOutbox, Long> {

    /** Rows still unpublished after the grace period, so a request thread mid-delivery is not raced. */
    List<ApprovalEventOutbox> findByPublishedAtIsNullAndCreatedAtBeforeOrderByIdAsc(Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ApprovalEventOutbox e
               SET e.publishedAt = :publishedAt
             WHERE e.id = :id
               AND e.publishedAt IS NULL
            """)
    int markPublished(@Param("id") Long id, @Param("publishedAt") Instant publishedAt);
}
