package io.mcpguard.settlement.telemetry;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogRecord, Long> {

    List<AuditLogRecord> findByOrderIdOrderByIdAsc(String orderId);

    List<AuditLogRecord> findByBatchIdOrderByIdAsc(String batchId);
}
