package io.mcpguard.settlement.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
    private final Map<String, PaymentExecutionResult> refundsByReference = new ConcurrentHashMap<>();
    private final AtomicInteger totalExecutions = new AtomicInteger(0);
    private final Set<String> timeoutOrders = ConcurrentHashMap.newKeySet();
    private final Set<String> timeoutAfterSettlementOrders = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean lookupOutage = new AtomicBoolean(false);
    private final AtomicReference<ExecutionPause> executionPause = new AtomicReference<>();

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
        PaymentExecutionResult existing = refundsByReference.get(reference);
        if (existing != null) {
            // Providers deduplicate on the idempotency reference; a repeat returns the original refund.
            return existing;
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
        refundsByReference.put(reference, result);
        if (timeoutAfterSettlementOrders.contains(orderId)) {
            throw new GatewayTimeoutException("Simulated response loss after settlement for order " + orderId);
        }
        return result;
    }

    @Override
    public Optional<PaymentExecutionResult> lookupRefund(String orderId, String idempotencyRef) {
        if (lookupOutage.get()) {
            throw new GatewayTimeoutException("Simulated gateway lookup outage");
        }
        return Optional.ofNullable(refundsByReference.get(reference(orderId, idempotencyRef)));
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

    /** Clears counters and failure modes. Recorded refunds stay, as they would at a real provider. */
    public void resetCounters() {
        orderExecutionCounts.clear();
        totalExecutions.set(0);
        timeoutOrders.clear();
        timeoutAfterSettlementOrders.clear();
        lookupOutage.set(false);
        executionPause.set(null);
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

    private record ExecutionPause(CountDownLatch entered, CountDownLatch release) {
    }

    public static class GatewayTimeoutException extends RuntimeException {
        public GatewayTimeoutException(String message) {
            super(message);
        }
    }
}
