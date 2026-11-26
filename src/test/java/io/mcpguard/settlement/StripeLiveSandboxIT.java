package io.mcpguard.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mcpguard.settlement.config.StripeProperties;
import io.mcpguard.settlement.domain.ProviderRefundLookup;
import io.mcpguard.settlement.domain.SettlementInvoiceItem;
import io.mcpguard.settlement.domain.SettlementInvoiceRepository;
import io.mcpguard.settlement.domain.StripePaymentGateway;
import io.mcpguard.settlement.guardrail.ApprovalGateService;
import io.mcpguard.settlement.guardrail.ApprovalGateService.RefundCommand;
import io.mcpguard.settlement.guardrail.ApprovalGateService.RefundDecision;
import io.mcpguard.settlement.guardrail.ApprovalStatus;
import io.mcpguard.settlement.guardrail.GuardrailExceptions;
import io.mcpguard.settlement.guardrail.IdempotencyRecord;
import io.mcpguard.settlement.guardrail.IdempotencyRecordRepository;
import io.mcpguard.settlement.guardrail.PayoutReconciliationJob;
import io.mcpguard.settlement.guardrail.PayoutResolution;
import io.mcpguard.settlement.guardrail.PayoutResolutionService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Optional live contract verification against Stripe Test Mode.
 * Skipped automatically in offline CI when {@code STRIPE_SECRET_KEY} is not set.
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_SECRET_KEY", matches = "sk_test_.+")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcpguard_stripe_live;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1",
        "guardrail.demo.seed-enabled=false",
        "guardrail.stripe.seed-test-charges=false"
})
@ActiveProfiles({"test", "stripe"})
class StripeLiveSandboxIT {

    private static final String REVIEWER_A = "finance-lead@company.com";
    private static final String REVIEWER_B = "controller@company.com";

    @Autowired
    private StripePaymentGateway stripeGateway;

    @Autowired
    private StripeProperties stripeProperties;

    @Autowired
    private SettlementInvoiceRepository invoiceRepository;

    @Autowired
    private ApprovalGateService approvalGateService;

    @Autowired
    private PayoutReconciliationJob reconciliationJob;

    @Autowired
    private PayoutResolutionService resolutionService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecords;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reconcilesLiveStripeRefundsCleanlyAndDetectsProviderDrift() {
        String suffix = shortSuffix();
        String orderId = "ORD-LIVE-RECON-" + suffix;
        String idemKey = "idem-live-recon-" + suffix;

        invoiceRepository.save(new SettlementInvoiceItem(
                "BATCH-STRIPE-LIVE",
                orderId,
                "recon@example.com",
                "USD",
                new BigDecimal("50.00"),
                new BigDecimal("80.00"),
                new BigDecimal("0.00")
        ));
        stripeGateway.ensureDemoCapturedPaymentIntent(
                orderId, new BigDecimal("80.00"), "USD", "recon@example.com");

        RefundDecision settled = approvalGateService.submitRefund(
                new RefundCommand(orderId, new BigDecimal("25.00"), "USD", "SHIPPING_OVERCHARGE", idemKey));
        assertThat(settled.status()).isEqualTo("EXECUTED");
        assertThat(settled.refundTxId()).startsWith("re_");

        // 1. Live lookup matches booked amount, currency, status, and refund ID -> zero mismatches
        assertThat(reconciliationJob.reconcile(Instant.now())).isZero();

        // 2. Simulate ledger/provider drift -> reconciliation job catches the mismatch against real Stripe
        jdbc.update("UPDATE refund_approval_order SET transaction_id = ? WHERE approval_id = ?",
                "re_drifted_" + suffix, settled.approvalId());

        assertThat(reconciliationJob.reconcile(Instant.now())).isEqualTo(1);
        String mismatchCode = jdbc.queryForObject(
                "SELECT mismatch_code FROM payout_reconciliation_exception WHERE approval_id = ?",
                String.class, settled.approvalId());
        assertThat(mismatchCode).isEqualTo(PayoutReconciliationJob.PROVIDER_TRANSACTION_MISMATCH);
    }

    @Test
    void freezesWhenChargeAlreadyRefundedOutOfBandAndReleasesViaFourEyes() throws Exception {
        String suffix = shortSuffix();
        String orderId = "ORD-LIVE-DEPLETED-" + suffix;
        String idemKey = "idem-live-depleted-" + suffix;

        invoiceRepository.save(new SettlementInvoiceItem(
                "BATCH-STRIPE-LIVE",
                orderId,
                "depleted@example.com",
                "USD",
                new BigDecimal("40.00"),
                new BigDecimal("60.00"),
                new BigDecimal("0.00")
        ));
        String paymentIntentId = stripeGateway.ensureDemoCapturedPaymentIntent(
                orderId, new BigDecimal("60.00"), "USD", "depleted@example.com");

        // Out-of-band manual full refund directly on Stripe (simulating someone clicking Refund in Stripe Dashboard)
        refundDirectlyOnStripe(paymentIntentId, 6000L);

        // Agent attempts a $20.00 refund that looks valid locally -> Stripe returns 400 charge_already_refunded
        RefundCommand agentAttempt = new RefundCommand(
                orderId, new BigDecimal("20.00"), "USD", "DUPLICATE_CHARGE", idemKey);
        assertThatThrownBy(() -> approvalGateService.submitRefund(agentAttempt))
                .isInstanceOf(GuardrailExceptions.PayoutOutcomeUnknownException.class);

        String approvalId = jdbc.queryForObject(
                "SELECT approval_id FROM refund_approval_order WHERE order_id = ?", String.class, orderId);
        String frozenStatus = jdbc.queryForObject(
                "SELECT status FROM refund_approval_order WHERE approval_id = ?", String.class, approvalId);
        assertThat(frozenStatus).isEqualTo(ApprovalStatus.PAYOUT_UNKNOWN.name());
        assertThat(idempotencyRecords.findByIdempotencyKey(idemKey).orElseThrow().getState())
                .isEqualTo(IdempotencyRecord.State.UNKNOWN_SUSPENDED);

        // Real Stripe lookup confirms the agent's idempotency reference never created a refund
        ProviderRefundLookup lookup = stripeGateway.lookupRefund(orderId, idemKey);
        assertThat(lookup.state()).isEqualTo(ProviderRefundLookup.State.NOT_FOUND);

        // Cannot release before the 24h settlement window elapses when cancelSupported=false
        assertThatThrownBy(() -> resolutionService.propose(
                approvalId, REVIEWER_A, PayoutResolution.Action.RELEASE, null))
                .isInstanceOf(GuardrailExceptions.ApprovalStateConflictException.class)
                .hasMessageContaining("settlement window");

        // Once the settlement window has elapsed, two distinct reviewers release the frozen reservation
        jdbc.update("UPDATE refund_approval_order SET created_at = CURRENT_TIMESTAMP - INTERVAL '25' HOUR "
                + "WHERE approval_id = ?", approvalId);

        resolutionService.propose(approvalId, REVIEWER_A, PayoutResolution.Action.RELEASE, null);
        assertThatThrownBy(() -> resolutionService.approve(approvalId, REVIEWER_A))
                .isInstanceOf(GuardrailExceptions.SeparationOfDutiesException.class);

        RefundDecision released = resolutionService.approve(approvalId, REVIEWER_B);
        assertThat(released.status()).isEqualTo(ApprovalStatus.PAYOUT_RELEASED.name());
        assertThat(invoiceRepository.findByOrderId(orderId).orElseThrow().getAlreadyRefundedAmount())
                .isEqualByComparingTo("0.00");
    }

    private void refundDirectlyOnStripe(String paymentIntentId, long amountMinorUnits) throws Exception {
        String form = "payment_intent=" + URLEncoder.encode(paymentIntentId, StandardCharsets.UTF_8)
                + "&amount=" + amountMinorUnits;
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(stripeProperties.apiBaseUrl() + "/v1/refunds"))
                .timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + stripeProperties.secretKey().trim())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isEqualTo(200);
    }

    private static String shortSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
