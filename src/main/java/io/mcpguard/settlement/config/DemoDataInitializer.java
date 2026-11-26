package io.mcpguard.settlement.config;

import io.mcpguard.settlement.domain.OrderAnchorLockService;
import io.mcpguard.settlement.domain.SettlementInvoiceItem;
import io.mcpguard.settlement.domain.SettlementInvoiceRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds deterministic sample invoices on startup for quick-start verification.
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    public static final String DEMO_BATCH_ID = "BATCH-2026-11";

    private final GuardrailProperties properties;
    private final SettlementInvoiceRepository invoiceRepository;
    private final OrderAnchorLockService anchorLockService;

    public DemoDataInitializer(
            GuardrailProperties properties,
            SettlementInvoiceRepository invoiceRepository,
            OrderAnchorLockService anchorLockService) {
        this.properties = properties;
        this.invoiceRepository = invoiceRepository;
        this.anchorLockService = anchorLockService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.demo().seedEnabled() || invoiceRepository.count() > 0) {
            return;
        }

        List<SettlementInvoiceItem> seedItems = List.of(
                new SettlementInvoiceItem(
                        DEMO_BATCH_ID,
                        "ORD-2026-8840",
                        "alice@acme-saas.io",
                        "USD",
                        new BigDecimal("99.00"),
                        new BigDecimal("99.00"),
                        new BigDecimal("0.00")
                ),
                new SettlementInvoiceItem(
                        DEMO_BATCH_ID,
                        "ORD-2026-8841",
                        "billing@northwind.dev",
                        "USD",
                        new BigDecimal("120.00"),
                        new BigDecimal("300.00"),
                        new BigDecimal("0.00")
                ),
                new SettlementInvoiceItem(
                        DEMO_BATCH_ID,
                        "ORD-2026-8842",
                        "ops@globex.cloud",
                        "USD",
                        new BigDecimal("250.00"),
                        new BigDecimal("285.50"),
                        new BigDecimal("0.00")
                ),
                new SettlementInvoiceItem(
                        DEMO_BATCH_ID,
                        "ORD-2026-9900",
                        "merchant@retail-hub.com",
                        "USD",
                        new BigDecimal("100.00"),
                        new BigDecimal("400.00"),
                        new BigDecimal("0.00")
                )
        );

        invoiceRepository.saveAll(seedItems);
        seedItems.forEach(item -> anchorLockService.ensureAnchorExists(item.getOrderId()));
    }
}
