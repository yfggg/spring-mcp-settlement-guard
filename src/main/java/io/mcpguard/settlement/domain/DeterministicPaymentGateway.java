package io.mcpguard.settlement.domain;

import io.mcpguard.settlement.config.GuardrailProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Deterministic sandbox payment adapter with thread-safe per-order execution counters
 * so CI tests can assert at-most-once execution without external API credentials.
 *
 * <p>Failure modes for the {@code PAYOUT_UNKNOWN} path:
 * <ul>
 *   <li>{@link #simulateTimeoutForOrder}: the request never reaches the provider; no money moves.</li>
 *   <li>{@link #simulateTimeoutAfterSettlementForOrder}: the provider pays, then the response is lost.</li>
 *   <li>{@link #simulateLookupOutage}: refund lookups fail, as during a provider outage.</li>
 * </ul>
 */
@Component
public class DeterministicPaymentGateway implements PaymentGatewayPort {

    private final ConcurrentHashMap<String, AtomicInteger> orderExecutionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StoredRefund> refundsByReference = new ConcurrentHashMap<>();
    private final AtomicInteger totalExecutions = new AtomicInteger(0);
    private final Set<String> timeoutOrders = ConcurrentHashMap.newKeySet();
    private final Set<String> timeoutAfterSettlementOrders = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean lookupOutage = new AtomicBoolean(false);
    private final AtomicBoolean cancelSupported;
    private final AtomicReference<ExecutionPause> executionPause = new AtomicReference<>();
    private final boolean cancelSupportedByConfig;

    public DeterministicPaymentGateway(GuardrailProperties properties) {
        this.cancelSupportedByConfig = properties.provider().cancelSupported();
        this.cancelSupported = new AtomicBoolean(cancelSupportedByConfig);
    }

    @Override
    public PaymentExecutionResult executeRefund(
            String orderId,
            BigDecimal amount,
            String currency,
            String idempotencyRef) {
        if (timeoutOrders.contains(orderId)) {
            throw new GatewayTimeoutException("Simulated gateway read timeout for order " + orderId);
        }
        awaitPauseIfArmed();
        // Count every call that reaches the provider, including deduplicated repeats, so tests can assert
        // that the guardrail itself never dispatches the same payout twice.
        totalExecutions.incrementAndGet();
        orderExecutionCounts.computeIfAbsent(orderId, k -> new AtomicInteger(0)).incrementAndGet();
        String reference = reference(orderId, idempotencyRef);
        StoredRefund existing = refundsByReference.get(reference);
        if (existing != null && existing.state() == ProviderRefundLookup.State.SUCCEEDED && existing.refund() != null) {
            return existing.refund();
        }
        if (existing != null && existing.terminal()
                && (existing.state() == ProviderRefundLookup.State.CANCELLED
                || existing.state() == ProviderRefundLookup.State.FAILED)) {
            throw new GatewayTimeoutException("Provider will not pay idempotency reference " + reference);
        }

        String suffix = UUID.nameUUIDFromBytes(reference.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 10);
        PaymentExecutionResult result = new PaymentExecutionResult(
                "tx_" + suffix,
                orderId,
                DeterministicReconciler.scale(amount),
                currency,
                Instant.now()
        );
        refundsByReference.put(reference, StoredRefund.succeeded(result));
        if (timeoutAfterSettlementOrders.contains(orderId)) {
            throw new GatewayTimeoutException("Simulated response loss after settlement for order " + orderId);
        }
        return result;
    }

    @Override
    public ProviderRefundLookup lookupRefund(String orderId, String idempotencyRef) {
        if (lookupOutage.get()) {
            throw new GatewayTimeoutException("Simulated gateway lookup outage");
        }
        StoredRefund stored = refundsByReference.get(reference(orderId, idempotencyRef));
        return stored == null ? ProviderRefundLookup.notFound() : stored.toLookup();
    }

    @Override
    public void cancelRefund(String orderId, String idempotencyRef) {
        if (!cancelSupported.get()) {
            throw new CancelNotSupportedException("This provider cannot cancel refunds");
        }
        if (lookupOutage.get()) {
            throw new GatewayTimeoutException("Simulated gateway lookup outage");
        }
        String reference = reference(orderId, idempotencyRef);
        StoredRefund stored = refundsByReference.get(reference);
        if (stored != null && stored.state() == ProviderRefundLookup.State.SUCCEEDED) {
            return;
        }
        refundsByReference.put(reference, StoredRefund.cancelled());
    }

    @Override
    public boolean cancelSupported() {
        return cancelSupported.get();
    }

    public void setCancelSupported(boolean supported) {
        cancelSupported.set(supported);
    }

    /** Installs a provider state without going through {@link #executeRefund}. Used to exercise reconciliation. */
    public void recordProviderState(
            String orderId,
            String idempotencyRef,
            ProviderRefundLookup.State state,
            boolean terminal,
            String transactionId,
            BigDecimal amount,
            String currency) {
        PaymentExecutionResult result = state == ProviderRefundLookup.State.NOT_FOUND
                ? null
                : new PaymentExecutionResult(
                        transactionId,
                        orderId,
                        amount == null ? null : DeterministicReconciler.scale(amount),
                        currency,
                        Instant.now());
        String reference = reference(orderId, idempotencyRef);
        if (state == ProviderRefundLookup.State.NOT_FOUND) {
            refundsByReference.remove(reference);
            return;
        }
        refundsByReference.put(reference, new StoredRefund(state, terminal, result));
    }

    public int getExecutionCountForOrder(String orderId) {
        AtomicInteger count = orderExecutionCounts.get(orderId);
        return count == null ? 0 : count.get();
    }

    public int getTotalExecutions() {
        return totalExecutions.get();
    }

    public void simulateTimeoutForOrder(String orderId) {
        timeoutOrders.add(orderId);
    }

    public void simulateTimeoutAfterSettlementForOrder(String orderId) {
        timeoutAfterSettlementOrders.add(orderId);
    }

    public void clearTimeoutForOrder(String orderId) {
        timeoutOrders.remove(orderId);
        timeoutAfterSettlementOrders.remove(orderId);
    }

    public void simulateLookupOutage(boolean outage) {
        lookupOutage.set(outage);
    }

    /**
     * Test hook. The next gateway call counts down {@code entered} and then waits for {@code release}
     * before it is counted as an execution. Production callers never arm this.
     */
    public void pauseNextExecution(CountDownLatch entered, CountDownLatch release) {
        executionPause.set(new ExecutionPause(entered, release));
    }

    /** Clears counters, failure modes, and recorded provider refunds. */
    public void resetCounters() {
        orderExecutionCounts.clear();
        totalExecutions.set(0);
        timeoutOrders.clear();
        timeoutAfterSettlementOrders.clear();
        lookupOutage.set(false);
        executionPause.set(null);
        refundsByReference.clear();
        cancelSupported.set(cancelSupportedByConfig);
    }

    private static String reference(String orderId, String idempotencyRef) {
        return orderId + ":" + idempotencyRef;
    }

    private void awaitPauseIfArmed() {
        ExecutionPause pause = executionPause.getAndSet(null);
        if (pause == null) {
            return;
        }
        pause.entered().countDown();
        try {
            if (!pause.release().await(10, TimeUnit.SECONDS)) {
                throw new GatewayTimeoutException("Simulated gateway pause was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayTimeoutException("Simulated gateway pause was interrupted");
        }
    }

    private record StoredRefund(
            ProviderRefundLookup.State state,
            boolean terminal,
            PaymentExecutionResult refund
    ) {
        static StoredRefund succeeded(PaymentExecutionResult refund) {
            return new StoredRefund(ProviderRefundLookup.State.SUCCEEDED, true, refund);
        }

        static StoredRefund cancelled() {
            return new StoredRefund(ProviderRefundLookup.State.CANCELLED, true, null);
        }

        ProviderRefundLookup toLookup() {
            return ProviderRefundLookup.of(state, terminal, refund);
        }
    }

    private record ExecutionPause(CountDownLatch entered, CountDownLatch release) {
    }

    public static class GatewayTimeoutException extends RuntimeException {
        public GatewayTimeoutException(String message) {
            super(message);
        }
    }
}
