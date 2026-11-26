package io.mcpguard.settlement.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderSettlementAnchorRepository extends JpaRepository<OrderSettlementAnchor, Long> {

    Optional<OrderSettlementAnchor> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM OrderSettlementAnchor a WHERE a.orderId = :orderId")
    Optional<OrderSettlementAnchor> findByOrderIdForUpdate(@Param("orderId") String orderId);
}
