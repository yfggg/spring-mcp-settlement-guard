package io.mcpguard.settlement.guardrail;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutReconciliationExceptionRepository extends JpaRepository<PayoutReconciliationException, Long> {

    Optional<PayoutReconciliationException> findByOpenApprovalId(String openApprovalId);
}
