package io.mcpguard.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.mcpguard.settlement.config.McpSecurityConfig;
import io.mcpguard.settlement.domain.DeterministicPaymentGateway;
import io.mcpguard.settlement.domain.SettlementInvoiceItem;
import io.mcpguard.settlement.domain.SettlementInvoiceRepository;
import io.mcpguard.settlement.guardrail.ApprovalGateService;
import io.mcpguard.settlement.guardrail.ApprovalGateService.RefundCommand;
import io.mcpguard.settlement.guardrail.ApprovalGateService.RefundDecision;
import io.mcpguard.settlement.guardrail.ApprovalWebhookNotifier;
import io.mcpguard.settlement.guardrail.IdempotencyKeyStore;
import io.mcpguard.settlement.guardrail.IdempotencyRecordRepository;
import io.mcpguard.settlement.guardrail.StaleExecutionSweeper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Failure injection and edge cases around the refund guardrail: key swaps, partial failures after money moved,
 * finance reconciliation races, and input or permission boundaries not covered by {@link ApprovalLifecycleIT}.
 * Failures are injected with temporary CHECK constraints so the production code runs unmodified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardrailEdgeCaseIT {

    private static final String REVIEWER = "finance-lead@company.com";

    @Autowired ApprovalGateService gate;
    @Autowired DeterministicPaymentGateway gateway;
    @Autowired SettlementInvoiceRepository invoiceRepository;
    @Autowired IdempotencyRecordRepository idempotencyRecords;
    @Autowired IdempotencyKeyStore keyStore;
    @Autowired StaleExecutionSweeper sweeper;
    @Autowired ApprovalWebhookNotifier notifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void reset() {
        gateway.resetCounters();
        notifier.clearEvents();
    }

    @Test
    void sameKeyFromTenConcurrentCallersPaysOnce() throws Exception {
        seed("ORD-EDGE-01", "100.00");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                return outcome(() -> gate.submitRefund(cmd("ORD-EDGE-01", "20.00", "FEE_DIFF", "edge-01")));
            }));
        }
        go.countDown();
        for (Future<String> f : results) {
            assertThat(f.get(20, TimeUnit.SECONDS)).doesNotContain("Conflict");
        }
        pool.shutdownNow();
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-01")).isEqualTo(1);
        assertThat(refunded("ORD-EDGE-01")).isEqualByComparingTo("20.00");
    }

    @Test
    void newKeyWhileFirstPayoutIsExecutingLinksToIt() throws Exception {
        seed("ORD-EDGE-02", "100.00");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gateway.pauseNextExecution(entered, release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RefundDecision> first = pool.submit(
                    () -> gate.submitRefund(cmd("ORD-EDGE-02", "20.00", "FEE_DIFF", "edge-02-a")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            RefundDecision swapped = gate.submitRefund(cmd("ORD-EDGE-02", "20.00", "FEE_DIFF", "edge-02-b"));
            assertThat(swapped.status()).isEqualTo("EXECUTING");
            assertThat(swapped.message()).isEqualTo(ApprovalGateService.DUPLICATE_IN_FLIGHT_MESSAGE);

            release.countDown();
            RefundDecision settled = first.get(10, TimeUnit.SECONDS);
            assertThat(swapped.approvalId()).isEqualTo(settled.approvalId());

            // After settlement the swapped key replays the real outcome instead of starting a new refund.
            RefundDecision swappedReplay = gate.submitRefund(cmd("ORD-EDGE-02", "20.00", "FEE_DIFF", "edge-02-b"));
            assertThat(swappedReplay.status()).isEqualTo("EXECUTED");
            assertThat(swappedReplay.refundTxId()).isEqualTo(settled.refundTxId());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-02")).isEqualTo(1);
        assertThat(refunded("ORD-EDGE-02")).isEqualByComparingTo("20.00");
    }

    @Test
    void newKeyWhileFirstRequestAwaitsApprovalLinksToIt() {
        seed("ORD-EDGE-03", "300.00");
        RefundDecision first = gate.submitRefund(cmd("ORD-EDGE-03", "60.00", "DISPUTE", "edge-03-a"));
        RefundDecision swapped = gate.submitRefund(cmd("ORD-EDGE-03", "60.00", "DISPUTE", "edge-03-b"));
        assertThat(swapped.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(swapped.approvalId()).isEqualTo(first.approvalId());
        assertThat(swapped.message()).doesNotContain("50").doesNotContain("200");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refund_approval_order WHERE order_id = 'ORD-EDGE-03'", Long.class)).isEqualTo(1L);

        RefundDecision approved = gate.approve(first.approvalId(), REVIEWER);
        RefundDecision swappedReplay = gate.submitRefund(cmd("ORD-EDGE-03", "60.00", "DISPUTE", "edge-03-b"));
        assertThat(swappedReplay.status()).isEqualTo("EXECUTED");
        assertThat(swappedReplay.refundTxId()).isEqualTo(approved.refundTxId());
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-03")).isEqualTo(1);
        assertThat(refunded("ORD-EDGE-03")).isEqualByComparingTo("60.00");
    }

    @Test
    void auditWriteFailureAfterPayoutKeepsTheReceipt() {
        seed("ORD-EDGE-04", "100.00");
        jdbc.execute("ALTER TABLE tool_execution_audit_log ADD CONSTRAINT edge_audit_block "
                + "CHECK (order_id IS NULL OR order_id <> 'ORD-EDGE-04')");
        try {
            RefundDecision first = gate.submitRefund(cmd("ORD-EDGE-04", "20.00", "FEE_DIFF", "edge-04"));
            assertThat(first.status()).isEqualTo("EXECUTED");
            assertThat(keyState("edge-04")).isEqualTo("COMPLETED");
            RefundDecision replay = gate.submitRefund(cmd("ORD-EDGE-04", "20.00", "FEE_DIFF", "edge-04"));
            assertThat(replay.refundTxId()).isEqualTo(first.refundTxId());
        } finally {
            jdbc.execute("ALTER TABLE tool_execution_audit_log DROP CONSTRAINT edge_audit_block");
        }
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-04")).isEqualTo(1);
    }

    @Test
    void databaseFailureBeforeReservationReleasesTheKey() {
        seed("ORD-EDGE-05", "100.00");
        jdbc.execute("ALTER TABLE refund_approval_order ADD CONSTRAINT edge_approval_block CHECK (order_id <> 'ORD-EDGE-05')");
        try {
            assertThat(outcome(() -> gate.submitRefund(cmd("ORD-EDGE-05", "20.00", "FEE_DIFF", "edge-05"))))
                    .startsWith("EX:DataIntegrityViolationException");
            assertThat(keyState("edge-05")).isEqualTo("none");
        } finally {
            jdbc.execute("ALTER TABLE refund_approval_order DROP CONSTRAINT edge_approval_block");
        }
        assertThat(gate.submitRefund(cmd("ORD-EDGE-05", "20.00", "FEE_DIFF", "edge-05")).status()).isEqualTo("EXECUTED");
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-05")).isEqualTo(1);
    }

    @Test
    void receiptWriteFailureAfterPayoutStillReturnsTheSettledOutcome() {
        seed("ORD-EDGE-06", "100.00");
        jdbc.execute("ALTER TABLE idempotency_record ADD CONSTRAINT edge_receipt_block "
                + "CHECK (idempotency_key <> 'edge-06' OR state <> 'COMPLETED')");
        RefundDecision first;
        try {
            first = gate.submitRefund(cmd("ORD-EDGE-06", "20.00", "FEE_DIFF", "edge-06"));
            assertThat(first.status()).isEqualTo("EXECUTED");
            assertThat(first.refundTxId()).isNotBlank();
            assertThat(keyState("edge-06")).isEqualTo("IN_PROGRESS");
        } finally {
            jdbc.execute("ALTER TABLE idempotency_record DROP CONSTRAINT edge_receipt_block");
        }
        RefundDecision retry = gate.submitRefund(cmd("ORD-EDGE-06", "20.00", "FEE_DIFF", "edge-06"));
        assertThat(retry.status()).isEqualTo("EXECUTED");
        assertThat(retry.refundTxId()).isEqualTo(first.refundTxId());
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-06")).isEqualTo(1);
        assertThat(refunded("ORD-EDGE-06")).isEqualByComparingTo("20.00");
    }

    @Test
    void ledgerRejectionAfterGatewaySuccessFreezesThePayout() throws Exception {
        seed("ORD-EDGE-07", "100.00");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gateway.pauseNextExecution(entered, release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        String result;
        try {
            Future<String> first = pool.submit(
                    () -> outcome(() -> gate.submitRefund(cmd("ORD-EDGE-07", "20.00", "FEE_DIFF", "edge-07"))));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE settlement_invoice_item SET captured_amount = 0.00 WHERE order_id = 'ORD-EDGE-07'");
            release.countDown();
            result = first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(result).startsWith("EX:PayoutOutcomeUnknownException");
        String approvalId = approvalIdOf("ORD-EDGE-07");
        assertThat(statusOf(approvalId)).isEqualTo("PAYOUT_UNKNOWN");
        assertThat(keyState("edge-07")).isEqualTo("UNKNOWN_SUSPENDED");
        assertThat(outcome(() -> gate.confirmSettledPayout(approvalId, REVIEWER, "tx_edge_07_manual")))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(statusOf(approvalId)).isEqualTo("PAYOUT_UNKNOWN");
        assertThat(refunded("ORD-EDGE-07")).isEqualByComparingTo("0.00");
    }

    @Test
    void oneProviderTransactionIdCannotSettleTwoRefunds() {
        seed("ORD-EDGE-08A", "100.00");
        seed("ORD-EDGE-08B", "100.00");
        gateway.simulateTimeoutForOrder("ORD-EDGE-08A");
        gateway.simulateTimeoutForOrder("ORD-EDGE-08B");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-08A", "20.00", "FEE_DIFF", "edge-08a")));
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-08B", "20.00", "FEE_DIFF", "edge-08b")));

        assertThat(gate.confirmSettledPayout(approvalIdOf("ORD-EDGE-08A"), REVIEWER, "tx_edge_shared").status())
                .isEqualTo("EXECUTED");
        assertThat(outcome(() -> gate.confirmSettledPayout(approvalIdOf("ORD-EDGE-08B"), REVIEWER, "tx_edge_shared")))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(statusOf(approvalIdOf("ORD-EDGE-08B"))).isEqualTo("PAYOUT_UNKNOWN");
        assertThat(refunded("ORD-EDGE-08B")).isEqualByComparingTo("0.00");
    }

    @Test
    void unpublishedOutboxRowIsRedeliveredBySweeper() {
        jdbc.update("INSERT INTO approval_event_outbox "
                + "(approval_id, order_id, amount, currency, status, transaction_id, reviewer_id, created_at) "
                + "VALUES ('apr_edge00000009', 'ORD-EDGE-09', 10.00, 'USD', 'EXECUTED', 'tx_edge_09', 'r@x', CURRENT_TIMESTAMP)");
        sweeper.sweep(Instant.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_event_outbox "
                + "WHERE approval_id = 'apr_edge00000009' AND published_at IS NOT NULL", Long.class)).isEqualTo(1L);
        assertThat(notifier.getPublishedEvents())
                .extracting(ApprovalWebhookNotifier.ApprovalDecisionEvent::approvalId)
                .contains("apr_edge00000009");
    }

    @Test
    void unknownOrderLeavesNoLockAnchorOrKey() {
        assertThat(outcome(() -> gate.submitRefund(cmd("ORD-EDGE-10-NOPE", "10.00", "FEE_DIFF", "edge-10"))))
                .startsWith("EX:InvalidSettlementRequestException");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_settlement_anchor WHERE order_id = 'ORD-EDGE-10-NOPE'", Long.class)).isZero();
        assertThat(keyState("edge-10")).isEqualTo("none");
    }

    @Test
    void keyFrozenByCrashBeforeReservationIsRecoveredOnRetry() {
        seed("ORD-EDGE-11", "100.00");
        RefundCommand command = cmd("ORD-EDGE-11", "15.00", "FEE_DIFF", "edge-11");
        keyStore.tryClaimOrReplay("edge-11", ApprovalGateService.computeRequestFingerprint(command));
        jdbc.update("UPDATE idempotency_record SET updated_at = CURRENT_TIMESTAMP - INTERVAL '1' HOUR "
                + "WHERE idempotency_key = 'edge-11'");
        sweeper.sweep(Instant.now());
        assertThat(keyState("edge-11")).isEqualTo("UNKNOWN_SUSPENDED");

        RefundDecision retry = gate.submitRefund(command);
        assertThat(retry.status()).isEqualTo("EXECUTED");
        assertThat(keyState("edge-11")).isEqualTo("COMPLETED");
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-11")).isEqualTo(1);
    }

    @Test
    void tamperedReasonCodeFailsSnapshotVerification() {
        seed("ORD-EDGE-12", "300.00");
        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-12", "120.00", "DISPUTE", "edge-12"));
        jdbc.update("UPDATE refund_approval_order SET reason_code = 'GOODWILL' WHERE approval_id = ?", paused.approvalId());
        assertThat(outcome(() -> gate.approve(paused.approvalId(), REVIEWER))).startsWith("EX:TamperedSnapshotException");
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-12")).isZero();
    }

    @Test
    void concurrentConfirmAndReleaseLeaveOneConsistentOutcome() throws Exception {
        seed("ORD-EDGE-13", "100.00");
        gateway.simulateTimeoutForOrder("ORD-EDGE-13");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-13", "20.00", "FEE_DIFF", "edge-13")));
        String approvalId = approvalIdOf("ORD-EDGE-13");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<String> confirm = pool.submit(() -> {
            go.await(5, TimeUnit.SECONDS);
            return outcome(() -> gate.confirmSettledPayout(approvalId, REVIEWER, "tx_edge_13"));
        });
        Future<String> release = pool.submit(() -> {
            go.await(5, TimeUnit.SECONDS);
            return outcome(() -> gate.releaseUnknownPayout(approvalId, REVIEWER));
        });
        go.countDown();
        List<String> outcomes = List.of(confirm.get(20, TimeUnit.SECONDS), release.get(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertThat(outcomes).filteredOn(o -> o.startsWith("EX:")).hasSize(1);
        String finalStatus = statusOf(approvalId);
        if ("EXECUTED".equals(finalStatus)) {
            assertThat(refunded("ORD-EDGE-13")).isEqualByComparingTo("20.00");
        } else {
            assertThat(finalStatus).isEqualTo("PAYOUT_RELEASED");
            assertThat(refunded("ORD-EDGE-13")).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void confirmAndReleaseRejectRequestsThatAreNotFrozen() {
        seed("ORD-EDGE-14", "300.00");
        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-14", "120.00", "DISPUTE", "edge-14"));
        assertThat(outcome(() -> gate.confirmSettledPayout(paused.approvalId(), REVIEWER, "tx_edge_14")))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(outcome(() -> gate.releaseUnknownPayout(paused.approvalId(), REVIEWER)))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(statusOf(paused.approvalId())).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void financeEndpointsRejectAgentKeysWrongKeysAndMalformedTransactionIds() throws Exception {
        seed("ORD-EDGE-15", "300.00");
        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-15", "120.00", "DISPUTE", "edge-15"));
        assertThat(status(get("/api/approvals/{id}", paused.approvalId())
                .header(McpSecurityConfig.HEADER_AGENT_API_KEY, "test-agent-key"))).isEqualTo(403);
        assertThat(status(post("/api/approvals/{id}/release-payout", paused.approvalId())
                .header(McpSecurityConfig.HEADER_AGENT_API_KEY, "test-agent-key")
                .header(McpSecurityConfig.HEADER_REVIEWER_ID, "rogue"))).isEqualTo(403);
        assertThat(status(get("/api/approvals/{id}", paused.approvalId())
                .header(McpSecurityConfig.HEADER_REVIEWER_KEY, "wrong-key")
                .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER))).isEqualTo(401);
        assertThat(status(post("/api/approvals/{id}/confirm-payout", paused.approvalId())
                .header(McpSecurityConfig.HEADER_REVIEWER_KEY, "test-reviewer-key")
                .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"bad id!\"}"))).isEqualTo(400);
    }

    @Test
    void serviceLayerRejectsOtherCurrenciesExtraPrecisionAndNegativeAmounts() {
        seed("ORD-EDGE-16", "100.00");
        assertThat(outcome(() -> gate.submitRefund(
                new RefundCommand("ORD-EDGE-16", new BigDecimal("10.00"), "EUR", "FEE_DIFF", "edge-16-a"))))
                .startsWith("EX:InvalidSettlementRequestException");
        assertThat(outcome(() -> gate.submitRefund(
                new RefundCommand("ORD-EDGE-16", new BigDecimal("10.005"), "USD", "FEE_DIFF", "edge-16-b"))))
                .startsWith("EX:InvalidSettlementRequestException");
        assertThat(outcome(() -> gate.submitRefund(
                new RefundCommand("ORD-EDGE-16", new BigDecimal("-1.00"), "USD", "FEE_DIFF", "edge-16-c"))))
                .startsWith("EX:InvalidSettlementRequestException");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key LIKE 'edge-16-%'", Long.class)).isZero();
    }

    @Test
    void refundsOlderThan24HoursLeaveTheVelocityWindow() {
        seed("ORD-EDGE-17", "500.00");
        for (int i = 1; i <= 4; i++) {
            assertThat(gate.submitRefund(cmd("ORD-EDGE-17", "45.00", "FEE_DIFF", "edge-17-" + i)).status())
                    .isEqualTo("EXECUTED");
        }
        jdbc.update("UPDATE refund_approval_order SET created_at = CURRENT_TIMESTAMP - INTERVAL '25' HOUR "
                + "WHERE order_id = 'ORD-EDGE-17'");
        assertThat(gate.submitRefund(cmd("ORD-EDGE-17", "45.00", "FEE_DIFF", "edge-17-5")).status())
                .isEqualTo("EXECUTED");
    }

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private void seed(String orderId, String captured) {
        if (invoiceRepository.findByOrderId(orderId).isEmpty()) {
            invoiceRepository.save(new SettlementInvoiceItem("BATCH-EDGE", orderId, "qa@example.com", "USD",
                    new BigDecimal(captured), new BigDecimal(captured), new BigDecimal("0.00")));
        }
    }

    private BigDecimal refunded(String orderId) {
        return invoiceRepository.findByOrderId(orderId).orElseThrow().getAlreadyRefundedAmount();
    }

    private String keyState(String key) {
        return idempotencyRecords.findByIdempotencyKey(key).map(r -> r.getState().name()).orElse("none");
    }

    private String approvalIdOf(String orderId) {
        return jdbc.queryForObject(
                "SELECT approval_id FROM refund_approval_order WHERE order_id = ? ORDER BY id LIMIT 1", String.class, orderId);
    }

    private String statusOf(String approvalId) {
        return jdbc.queryForObject("SELECT status FROM refund_approval_order WHERE approval_id = ?", String.class, approvalId);
    }

    private static RefundCommand cmd(String orderId, String amount, String reason, String key) {
        return new RefundCommand(orderId, new BigDecimal(amount), "USD", reason, key);
    }

    /** Runs the call and returns the decision status, or {@code EX:<ExceptionType>:<message>} if it threw. */
    private static String outcome(Callable<RefundDecision> call) {
        try {
            RefundDecision d = call.call();
            return d.status() + "/" + d.approvalId() + "/" + d.refundTxId();
        } catch (Exception e) {
            return "EX:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }
}
