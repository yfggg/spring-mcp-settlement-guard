package io.mcpguard.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Per-order row-level lock anchor used to serialize concurrent refund requests
 * targeting the same order under SELECT ... FOR UPDATE.
 */
@Entity
@Table(
        name = "order_settlement_anchor",
        uniqueConstraints = @UniqueConstraint(name = "uk_anchor_order_id", columnNames = "order_id")
)
public class OrderSettlementAnchor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_locked_at", nullable = false)
    private Instant lastLockedAt;

    protected OrderSettlementAnchor() {
    }

    public OrderSettlementAnchor(String orderId, Instant now) {
        this.orderId = orderId;
        this.createdAt = now;
        this.lastLockedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void touch(Instant now) {
        this.lastLockedAt = now;
    }
}
