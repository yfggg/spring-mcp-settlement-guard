package io.mcpguard.settlement.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Deterministic sandbox payment adapter with thread-safe per-order execution counters
 * so CI tests can assert at-most-once execution without external API credentials.
 * Supports injected gateway timeouts to exercise the PAYOUT_UNKNOWN path.
 */
@Component
public class DeterministicPaymentGateway implements PaymentGatewayPort {

    private final ConcurrentHashMap<String, AtomicInteger> orderExecutionCounts = new ConcurrentHashMap<>();
    private final AtomicInteger totalExecutions = new AtomicInteger(0);
    private final Set<String> timeoutOrders = ConcurrentHashMap.newKeySet();
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
        totalExecutions.incrementAndGet();
        orderExecutionCounts.computeIfAbsent(orderId, k -> new AtomicInteger(0)).incrementAndGet();

        String suffix = UUID.nameUUIDFromBytes((orderId + ":" + idempotencyRef).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 10);
        return new PaymentExecutionResult(
                "tx_" + suffix,
                orderId,
                DeterministicReconciler.scale(amount),
                currency,
                Instant.now()
        );
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

    public void clearTimeoutForOrder(String orderId) {
        timeoutOrders.remove(orderId);
    }

    /**
     * Test hook. The next gateway call counts down {@code entered} and then waits for {@code release}
     * before it is counted as an execution. Production callers never arm this.
     */
    public void pauseNextExecution(CountDownLatch entered, CountDownLatch release) {
        executionPause.set(new ExecutionPause(entered, release));
    }

    public void resetCounters() {
        orderExecutionCounts.clear();
        totalExecutions.set(0);
        timeoutOrders.clear();
        executionPause.set(null);
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
