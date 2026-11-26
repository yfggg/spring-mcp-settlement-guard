package io.mcpguard.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;

@Entity
@Table(
        name = "settlement_invoice_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoice_order_id", columnNames = "order_id"),
        indexes = @Index(name = "idx_invoice_batch_id", columnList = "batch_id")
)
public class SettlementInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "customer_email", nullable = false, length = 128)
    private String customerEmail;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "expected_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "captured_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal capturedAmount;

    @Column(name = "already_refunded_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal alreadyRefundedAmount;

    protected SettlementInvoiceItem() {
    }

    public SettlementInvoiceItem(
            String batchId,
            String orderId,
            String customerEmail,
            String currency,
            BigDecimal expectedAmount,
            BigDecimal capturedAmount,
            BigDecimal alreadyRefundedAmount) {
        this.batchId = batchId;
        this.orderId = orderId;
        this.customerEmail = customerEmail;
        this.currency = currency;
        this.expectedAmount = expectedAmount;
        this.capturedAmount = capturedAmount;
        this.alreadyRefundedAmount = alreadyRefundedAmount;
    }

    public Long getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public BigDecimal getCapturedAmount() {
        return capturedAmount;
    }

    public BigDecimal getAlreadyRefundedAmount() {
        return alreadyRefundedAmount;
    }
}
