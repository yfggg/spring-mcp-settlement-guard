package io.mcpguard.settlement.guardrail;

import io.mcpguard.settlement.config.GuardrailProperties;
import io.mcpguard.settlement.domain.PaymentGatewayPort;
import io.mcpguard.settlement.domain.ProviderRefundLookup;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Compares recently booked and released refunds with the provider. A mismatch is stored once and logged.
 * The job does not move money or change approval status.
 */
@Component
public class PayoutReconciliationJob {

    public static final String PROVIDER_SUCCEEDED_AFTER_RELEASE = "PROVIDER_SUCCEEDED_AFTER_RELEASE";
    public static final String PROVIDER_PENDING_AFTER_RELEASE = "PROVIDER_PENDING_AFTER_RELEASE";
    public static final String BOOKED_REFUND_NOT_SUCCEEDED = "BOOKED_REFUND_NOT_SUCCEEDED";
    public static final String PROVIDER_AMOUNT_MISMATCH = "PROVIDER_AMOUNT_MISMATCH";
    public static final String PROVIDER_TRANSACTION_MISMATCH = "PROVIDER_TRANSACTION_MISMATCH";
    public static final String PROVIDER_LOOKUP_FAILED = "PROVIDER_LOOKUP_FAILED";

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciliationJob.class);
    private static final List<ApprovalStatus> BOOKED = List.of(ApprovalStatus.EXECUTED, ApprovalStatus.PAYOUT_RELEASED);

    private final GuardrailProperties properties;
    private final RefundApprovalOrderRepository approvalRepository;
    private final PayoutReconciliationExceptionRepository exceptionRepository;
    private final PaymentGatewayPort paymentGateway;

    public PayoutReconciliationJob(
            GuardrailProperties properties,
            RefundApprovalOrderRepository approvalRepository,
            PayoutReconciliationExceptionRepository exceptionRepository,
            PaymentGatewayPort paymentGateway) {
        this.properties = properties;
        this.approvalRepository = approvalRepository;
        this.exceptionRepository = exceptionRepository;
        this.paymentGateway = paymentGateway;
    }

    @Scheduled(
            initialDelayString = "${guardrail.provider.reconcile-interval-ms}",
            fixedDelayString = "${guardrail.provider.reconcile-interval-ms}")
    public void scheduledReconcile() {
        reconcile(Instant.now());
    }

    public int reconcile(Instant now) {
        Instant since = now.minus(Duration.ofDays(properties.provider().reconcileLookbackDays()));
        int opened = 0;
        for (RefundApprovalOrder order : approvalRepository.findBookedSince(BOOKED, since)) {
            if (record(order, compare(order), now)) {
                opened++;
            }
        }
        return opened;
    }

    private Mismatch compare(RefundApprovalOrder order) {
        ProviderRefundLookup lookup;
        try {
            lookup = paymentGateway.lookupRefund(order.getOrderId(), order.getIdempotencyKey());
        } catch (RuntimeException ex) {
            return new Mismatch(PROVIDER_LOOKUP_FAILED, "UNAVAILABLE",
                    "Provider lookup failed for " + order.getApprovalId());
        }
        if (order.getStatus() == ApprovalStatus.PAYOUT_RELEASED) {
            if (lookup.state() == ProviderRefundLookup.State.SUCCEEDED) {
                return new Mismatch(PROVIDER_SUCCEEDED_AFTER_RELEASE, lookup.state().name(),
                        "Released refund " + order.getApprovalId() + " is succeeded at the provider");
            }
            if (lookup.state() == ProviderRefundLookup.State.PENDING) {
                return new Mismatch(PROVIDER_PENDING_AFTER_RELEASE, lookup.state().name(),
                        "Released refund " + order.getApprovalId() + " is still processing at the provider");
            }
            return null;
        }
        if (lookup.state() != ProviderRefundLookup.State.SUCCEEDED || lookup.refund() == null) {
            return new Mismatch(BOOKED_REFUND_NOT_SUCCEEDED, lookup.state().name(),
                    "Booked refund " + order.getApprovalId() + " is " + lookup.state() + " at the provider");
        }
        if (lookup.refund().amount().compareTo(order.getAmount()) != 0
                || !lookup.refund().currency().equalsIgnoreCase(order.getCurrency())) {
            return new Mismatch(PROVIDER_AMOUNT_MISMATCH, lookup.state().name(),
                    "Provider amount or currency differs for " + order.getApprovalId());
        }
        if (order.getTransactionId() != null && !order.getTransactionId().equals(lookup.refund().transactionId())) {
            return new Mismatch(PROVIDER_TRANSACTION_MISMATCH, lookup.state().name(),
                    "Provider transaction id differs for " + order.getApprovalId());
        }
        return null;
    }

    /** @return true when a new open exception was inserted */
    private boolean record(RefundApprovalOrder order, Mismatch mismatch, Instant now) {
        if (mismatch == null) {
            return false;
        }
        Optional<PayoutReconciliationException> open = exceptionRepository.findByOpenApprovalId(order.getApprovalId());
        if (open.isPresent()) {
            return false;
        }
        exceptionRepository.save(new PayoutReconciliationException(
                order.getApprovalId(), mismatch.code(), mismatch.providerState(), mismatch.detail(), now));
        log.warn("Payout reconciliation mismatch: approvalId={}, code={}, providerState={}",
                order.getApprovalId(), mismatch.code(), mismatch.providerState());
        return true;
    }

    private record Mismatch(String code, String providerState, String detail) {
    }
}
