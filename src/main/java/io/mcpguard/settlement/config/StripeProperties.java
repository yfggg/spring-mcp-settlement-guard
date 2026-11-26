package io.mcpguard.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the optional {@code stripe} profile adapter ({@code StripePaymentGateway}).
 *
 * @param secretKey       Stripe API secret key ({@code sk_test_...} in sandbox mode)
 * @param apiBaseUrl      Stripe REST API origin
 * @param seedTestCharges whether startup should create matching test PaymentIntents for seeded demo orders
 */
@ConfigurationProperties(prefix = "guardrail.stripe")
public record StripeProperties(
        String secretKey,
        @DefaultValue("https://api.stripe.com") String apiBaseUrl,
        @DefaultValue("true") boolean seedTestCharges
) {
}
