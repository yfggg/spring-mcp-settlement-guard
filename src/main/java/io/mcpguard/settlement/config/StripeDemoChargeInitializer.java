package io.mcpguard.settlement.config;

import io.mcpguard.settlement.domain.SettlementInvoiceItem;
import io.mcpguard.settlement.domain.SettlementInvoiceRepository;
import io.mcpguard.settlement.domain.StripePaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * In the {@code stripe} profile, provisions matching test-mode PaymentIntents in Stripe for the
 * seeded demo invoices so real refunds can be issued immediately during sandbox verification.
 */
@Component
@Profile("stripe")
@Order(20)
public class StripeDemoChargeInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StripeDemoChargeInitializer.class);

    private final GuardrailProperties guardrailProperties;
    private final StripeProperties stripeProperties;
    private final SettlementInvoiceRepository invoiceRepository;
    private final StripePaymentGateway stripePaymentGateway;

    public StripeDemoChargeInitializer(
            GuardrailProperties guardrailProperties,
            StripeProperties stripeProperties,
            SettlementInvoiceRepository invoiceRepository,
            StripePaymentGateway stripePaymentGateway) {
        this.guardrailProperties = guardrailProperties;
        this.stripeProperties = stripeProperties;
        this.invoiceRepository = invoiceRepository;
        this.stripePaymentGateway = stripePaymentGateway;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!guardrailProperties.demo().seedEnabled() || !stripeProperties.seedTestCharges()) {
            return;
        }
        for (SettlementInvoiceItem item : invoiceRepository.findAll()) {
            stripePaymentGateway.ensureDemoCapturedPaymentIntent(
                    item.getOrderId(),
                    item.getCapturedAmount(),
                    item.getCurrency(),
                    item.getCustomerEmail()
            );
        }
        log.info("Stripe sandbox ready: demo invoices are backed by captured test PaymentIntents.");
    }
}
