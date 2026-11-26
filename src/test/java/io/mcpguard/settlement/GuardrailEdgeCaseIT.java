package io.mcpguard.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.mcpguard.settlement.config.McpSecurityConfig;
import io.mcpguard.settlement.domain.DeterministicPaymentGateway;
import io.mcpguard.settlement.domain.ProviderRefundLookup;
import io.mcpguard.settlement.domain.SettlementInvoiceItem;
import io.mcpguard.settlement.domain.SettlementInvoiceRepository;
import io.mcpguard.settlement.guardrail.ApprovalGateService;
import io.mcpguard.settlement.guardrail.ApprovalGateService.RefundCommand;
import io.mcpguard.settlement.guardrail.ApprovalGateService.RefundDecision;
import io.mcpguard.settlement.guardrail.ApprovalStatus;
import io.mcpguard.settlement.guardrail.ApprovalWebhookNotifier;
import io.mcpguard.settlement.guardrail.IdempotencyKeyStore;
import io.mcpguard.settlement.guardrail.IdempotencyRecordRepository;
import io.mcpguard.settlement.guardrail.PayoutReconciliationJob;
import io.mcpguard.settlement.guardrail.PayoutResolution;
import io.mcpguard.settlement.guardrail.PayoutResolutionService;
import io.mcpguard.settlement.guardrail.StaleExecutionSweeper;
import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Failure injection and edge cases around the refund guardrail: key swaps, partial failures after money moved,
 * four-eyes payout resolution, reviewer token validation, and input or permission boundaries not covered by
 * {@link ApprovalLifecycleIT}. Failures are injected with temporary CHECK constraints so production code runs
 * unmodified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardrailEdgeCaseIT {

    private static final String REVIEWER = "finance-lead@company.com";
    private static final String SECOND_REVIEWER = "controller@company.com";
    private static final String THIRD_REVIEWER = "auditor@company.com";
    private static final String AGENT_KEY = "test-agent-key";

    @Autowired ApprovalGateService gate;
    @Autowired PayoutResolutionService resolutions;
    @Autowired DeterministicPaymentGateway gateway;
    @Autowired SettlementInvoiceRepository invoiceRepository;
    @Autowired IdempotencyRecordRepository idempotencyRecords;
    @Autowired IdempotencyKeyStore keyStore;
    @Autowired StaleExecutionSweeper sweeper;
    @Autowired PayoutReconciliationJob reconciliation;
    @Autowired ApprovalWebhookNotifier notifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void reset() {
        gateway.resetCounters();
        notifier.clearEvents();
    }

    // ---- Idempotency and key swaps ----

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
    void linkedKeyFrozenByCrashStillResolvesToTheOriginalRefund() {
        seed("ORD-EDGE-18", "300.00");
        RefundDecision first = gate.submitRefund(cmd("ORD-EDGE-18", "70.00", "DISPUTE", "edge-18-a"));
        jdbc.execute("ALTER TABLE idempotency_record ADD CONSTRAINT edge_link_block "
                + "CHECK (idempotency_key <> 'edge-18-b' OR state <> 'COMPLETED')");
        try {
            RefundDecision swapped = gate.submitRefund(cmd("ORD-EDGE-18", "70.00", "DISPUTE", "edge-18-b"));
            assertThat(swapped.approvalId()).isEqualTo(first.approvalId());
            assertThat(keyState("edge-18-b")).isEqualTo("IN_PROGRESS");
        } finally {
            jdbc.execute("ALTER TABLE idempotency_record DROP CONSTRAINT edge_link_block");
        }
        RefundDecision approved = gate.approve(first.approvalId(), REVIEWER);
        jdbc.update("UPDATE idempotency_record SET updated_at = CURRENT_TIMESTAMP - INTERVAL '1' HOUR "
                + "WHERE idempotency_key = 'edge-18-b'");
        sweeper.sweep(Instant.now());
        assertThat(keyState("edge-18-b")).isEqualTo("UNKNOWN_SUSPENDED");

        RefundDecision retry = gate.submitRefund(cmd("ORD-EDGE-18", "70.00", "DISPUTE", "edge-18-b"));
        assertThat(retry.approvalId()).isEqualTo(first.approvalId());
        assertThat(retry.status()).isEqualTo("EXECUTED");
        assertThat(retry.refundTxId()).isEqualTo(approved.refundTxId());
        assertThat(gateway.getExecutionCountForOrder("ORD-EDGE-18")).isEqualTo(1);
        assertThat(refunded("ORD-EDGE-18")).isEqualByComparingTo("70.00");
    }

    // ---- Partial failures after money moved ----

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

        // The gateway did pay, so CONFIRM is proposed; the ledger still refuses it on approval.
        String providerTx = gateway.lookupRefund("ORD-EDGE-07", "edge-07").refund().transactionId();
        resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.CONFIRM, providerTx);
        assertThat(outcome(() -> resolutions.approve(approvalId, SECOND_REVIEWER)))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(statusOf(approvalId)).isEqualTo("PAYOUT_UNKNOWN");
        assertThat(refunded("ORD-EDGE-07")).isEqualByComparingTo("0.00");
        assertThat(resolutions.history(approvalId)).extracting(PayoutResolutionService.PayoutResolutionView::status)
                .containsExactly("PROPOSED");
    }

    // ---- Four-eyes payout resolution and gateway checks ----

    @Test
    void transactionIdMustMatchTheGatewayAndCanSettleOnlyOneRefund() {
        seed("ORD-EDGE-08A", "100.00");
        seed("ORD-EDGE-08B", "100.00");
        gateway.simulateTimeoutAfterSettlementForOrder("ORD-EDGE-08A");
        gateway.simulateTimeoutAfterSettlementForOrder("ORD-EDGE-08B");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-08A", "20.00", "FEE_DIFF", "edge-08a")));
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-08B", "20.00", "FEE_DIFF", "edge-08b")));
        String approvalA = approvalIdOf("ORD-EDGE-08A");
        String approvalB = approvalIdOf("ORD-EDGE-08B");
        String txA = gateway.lookupRefund("ORD-EDGE-08A", "edge-08a").refund().transactionId();

        // B's gateway record has a different id, so A's id is refused for B.
        assertThat(outcomeOf(() -> resolutions.propose(approvalB, REVIEWER, PayoutResolution.Action.CONFIRM, txA)))
                .startsWith("EX:ApprovalStateConflictException");

        resolutions.propose(approvalA, REVIEWER, PayoutResolution.Action.CONFIRM, txA);
        assertThat(resolutions.approve(approvalA, SECOND_REVIEWER).status()).isEqualTo("EXECUTED");

        // The database enforces the same rule even if application checks were bypassed.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE refund_approval_order SET transaction_id = ? WHERE approval_id = ?", txA, approvalB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(statusOf(approvalB)).isEqualTo("PAYOUT_UNKNOWN");
        assertThat(refunded("ORD-EDGE-08B")).isEqualByComparingTo("0.00");
    }

    @Test
    void releaseIsRefusedWhenTheGatewayShowsMoneyMovedAndConfirmWhenItShowsNothing() {
        seed("ORD-EDGE-19A", "100.00");
        seed("ORD-EDGE-19B", "100.00");
        gateway.simulateTimeoutAfterSettlementForOrder("ORD-EDGE-19A");
        gateway.simulateTimeoutForOrder("ORD-EDGE-19B");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-19A", "20.00", "FEE_DIFF", "edge-19a")));
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-19B", "20.00", "FEE_DIFF", "edge-19b")));

        assertThat(outcomeOf(() -> resolutions.propose(
                approvalIdOf("ORD-EDGE-19A"), REVIEWER, PayoutResolution.Action.RELEASE, null)))
                .startsWith("EX:ApprovalStateConflictException").contains("confirm it instead");
        assertThat(outcomeOf(() -> resolutions.propose(
                approvalIdOf("ORD-EDGE-19B"), REVIEWER, PayoutResolution.Action.CONFIRM, "tx_invented")))
                .startsWith("EX:ApprovalStateConflictException").contains("no refund");
        assertThat(resolutions.history(approvalIdOf("ORD-EDGE-19A"))).isEmpty();
        assertThat(resolutions.history(approvalIdOf("ORD-EDGE-19B"))).isEmpty();
    }

    @Test
    void gatewayOutageBlocksProposalsAndApprovals() {
        seed("ORD-EDGE-20", "100.00");
        gateway.simulateTimeoutForOrder("ORD-EDGE-20");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-20", "20.00", "FEE_DIFF", "edge-20")));
        String approvalId = approvalIdOf("ORD-EDGE-20");

        gateway.simulateLookupOutage(true);
        assertThat(outcomeOf(() -> resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.RELEASE, null)))
                .startsWith("EX:GatewayVerificationUnavailableException");

        gateway.simulateLookupOutage(false);
        resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.RELEASE, null);
        gateway.simulateLookupOutage(true);
        assertThat(outcome(() -> resolutions.approve(approvalId, SECOND_REVIEWER)))
                .startsWith("EX:GatewayVerificationUnavailableException");
        assertThat(statusOf(approvalId)).isEqualTo("PAYOUT_UNKNOWN");

        gateway.simulateLookupOutage(false);
        assertThat(resolutions.approve(approvalId, SECOND_REVIEWER).status()).isEqualTo("PAYOUT_RELEASED");
    }

    @Test
    void proposerCanWithdrawAndAFreshProposalIsAccepted() {
        seed("ORD-EDGE-21", "100.00");
        gateway.simulateTimeoutForOrder("ORD-EDGE-21");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-21", "20.00", "FEE_DIFF", "edge-21")));
        String approvalId = approvalIdOf("ORD-EDGE-21");

        resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.RELEASE, null);
        assertThat(outcomeOf(() -> resolutions.propose(approvalId, SECOND_REVIEWER, PayoutResolution.Action.RELEASE, null)))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(resolutions.reject(approvalId, REVIEWER).status()).isEqualTo("REJECTED");
        assertThat(outcome(() -> resolutions.approve(approvalId, SECOND_REVIEWER)))
                .startsWith("EX:ApprovalStateConflictException");

        resolutions.propose(approvalId, SECOND_REVIEWER, PayoutResolution.Action.RELEASE, null);
        assertThat(resolutions.approve(approvalId, REVIEWER).status()).isEqualTo("PAYOUT_RELEASED");
        assertThat(resolutions.history(approvalId)).extracting(PayoutResolutionService.PayoutResolutionView::status)
                .containsExactly("REJECTED", "APPLIED");
    }

    @Test
    void twoApproversRacingOnOneResolutionApplyItOnce() throws Exception {
        seed("ORD-EDGE-13", "100.00");
        gateway.simulateTimeoutAfterSettlementForOrder("ORD-EDGE-13");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-13", "20.00", "FEE_DIFF", "edge-13")));
        String approvalId = approvalIdOf("ORD-EDGE-13");
        String providerTx = gateway.lookupRefund("ORD-EDGE-13", "edge-13").refund().transactionId();
        resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.CONFIRM, providerTx);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<String> a = pool.submit(() -> {
            go.await(5, TimeUnit.SECONDS);
            return outcome(() -> resolutions.approve(approvalId, SECOND_REVIEWER));
        });
        Future<String> b = pool.submit(() -> {
            go.await(5, TimeUnit.SECONDS);
            return outcome(() -> resolutions.approve(approvalId, THIRD_REVIEWER));
        });
        go.countDown();
        List<String> outcomes = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertThat(outcomes).anyMatch(o -> o.startsWith("EXECUTED/"));
        assertThat(statusOf(approvalId)).isEqualTo("EXECUTED");
        assertThat(refunded("ORD-EDGE-13")).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_event_outbox WHERE approval_id = ? AND status = 'EXECUTED'",
                Long.class, approvalId)).isEqualTo(1L);
    }

    @Test
    void resolutionsAreRefusedForRequestsThatAreNotFrozen() {
        seed("ORD-EDGE-14", "300.00");
        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-14", "120.00", "DISPUTE", "edge-14"));
        assertThat(outcomeOf(() -> resolutions.propose(
                paused.approvalId(), REVIEWER, PayoutResolution.Action.CONFIRM, "tx_edge_14")))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(outcomeOf(() -> resolutions.propose(
                paused.approvalId(), REVIEWER, PayoutResolution.Action.RELEASE, null)))
                .startsWith("EX:ApprovalStateConflictException");
        assertThat(statusOf(paused.approvalId())).isEqualTo("PENDING_APPROVAL");
    }

    // ---- Reviewer authentication ----

    @Test
    void reviewerTokensAreCheckedForSignatureIssuerExpirySubjectAndRole() throws Exception {
        seed("ORD-EDGE-15", "300.00");
        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-15", "120.00", "DISPUTE", "edge-15"));
        String view = "/api/approvals/" + paused.approvalId();
        Duration ttl = Duration.ofMinutes(10);
        List<String> role = ReviewerTokens.FINANCE_ROLE;
        String secret = ReviewerTokens.TEST_SECRET;
        String issuer = ReviewerTokens.ISSUER;

        assertThat(status(get(view).header(HttpHeaders.AUTHORIZATION, ReviewerTokens.bearer(REVIEWER)))).isEqualTo(200);
        assertThat(status(get(view).header(HttpHeaders.AUTHORIZATION,
                bearer(ReviewerTokens.mint("another-secret-that-is-also-long-enough-123", issuer, REVIEWER, role, ttl)))))
                .isEqualTo(401);
        assertThat(status(get(view).header(HttpHeaders.AUTHORIZATION,
                bearer(ReviewerTokens.mint(secret, "someone-else", REVIEWER, role, ttl))))).isEqualTo(401);
        assertThat(status(get(view).header(HttpHeaders.AUTHORIZATION,
                bearer(ReviewerTokens.mint(secret, issuer, REVIEWER, role, Duration.ofMinutes(-5)))))).isEqualTo(401);
        assertThat(status(get(view).header(HttpHeaders.AUTHORIZATION,
                bearer(ReviewerTokens.mint(secret, issuer, REVIEWER, role, null))))).isEqualTo(401);
        assertThat(status(get(view).header(HttpHeaders.AUTHORIZATION,
                bearer(ReviewerTokens.mint(secret, issuer, null, role, ttl))))).isEqualTo(401);
        assertThat(status(get(view).header(HttpHeaders.AUTHORIZATION,
                bearer(ReviewerTokens.mint(secret, issuer, REVIEWER, List.of("SUPPORT_AGENT"), ttl))))).isEqualTo(403);
        assertThat(status(get(view).header("X-Reviewer-Id", REVIEWER))).isEqualTo(401);
    }

    @Test
    void financeEndpointsRejectAgentKeysAndMalformedTransactionIds() throws Exception {
        seed("ORD-EDGE-16X", "300.00");
        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-16X", "120.00", "DISPUTE", "edge-16x"));
        assertThat(status(get("/api/approvals/{id}", paused.approvalId())
                .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY))).isEqualTo(403);
        assertThat(status(post("/api/approvals/{id}/release-payout", paused.approvalId())
                .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY))).isEqualTo(403);
        assertThat(status(post("/api/approvals/{id}/payout-resolution/approve", paused.approvalId())
                .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY))).isEqualTo(403);
        assertThat(status(post("/api/approvals/{id}/confirm-payout", paused.approvalId())
                .header(HttpHeaders.AUTHORIZATION, ReviewerTokens.bearer(REVIEWER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"bad id!\"}"))).isEqualTo(400);
        // An agent key cannot be combined with a reviewer token to borrow the agent role.
        assertThat(status(post("/api/demo/simulate-agent-refund")
                .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                .header(HttpHeaders.AUTHORIZATION, ReviewerTokens.bearer(REVIEWER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(demoBody("ORD-EDGE-16X", "10.00", "FEE_DIFF", "edge-16x-mix")))).isEqualTo(403);
    }

    // ---- Demo endpoint status codes ----

    @Test
    void demoEndpointStatusReflectsWhetherMoneyMoved() throws Exception {
        seed("ORD-EDGE-22", "300.00");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gateway.pauseNextExecution(entered, release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RefundDecision> first = pool.submit(
                    () -> gate.submitRefund(cmd("ORD-EDGE-22", "20.00", "FEE_DIFF", "edge-22-a")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(demoStatus("ORD-EDGE-22", "20.00", "FEE_DIFF", "edge-22-a")).isEqualTo(202);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(demoStatus("ORD-EDGE-22", "20.00", "FEE_DIFF", "edge-22-a")).isEqualTo(200);

        gateway.simulateTimeoutForOrder("ORD-EDGE-22");
        outcome(() -> gate.submitRefund(cmd("ORD-EDGE-22", "25.00", "FEE_DIFF", "edge-22-b")));
        assertThat(demoStatus("ORD-EDGE-22", "25.00", "FEE_DIFF", "edge-22-b")).isEqualTo(502);

        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-22", "90.00", "DISPUTE", "edge-22-c"));
        gate.reject(paused.approvalId(), REVIEWER);
        assertThat(demoStatus("ORD-EDGE-22", "90.00", "DISPUTE", "edge-22-c")).isEqualTo(409);
    }

    // ---- Outbox ----

    @Test
    void unpublishedOutboxRowIsRedeliveredWithItsEventIdAfterTheGracePeriod() {
        jdbc.update("INSERT INTO approval_event_outbox "
                + "(event_id, approval_id, order_id, amount, currency, status, transaction_id, reviewer_id, created_at) "
                + "VALUES ('evt-edge-09-old', 'apr_edge00000009', 'ORD-EDGE-09', 10.00, 'USD', 'EXECUTED', 'tx_edge_09', 'r@x', "
                + "CURRENT_TIMESTAMP - INTERVAL '5' MINUTE)");
        jdbc.update("INSERT INTO approval_event_outbox "
                + "(event_id, approval_id, order_id, amount, currency, status, transaction_id, reviewer_id, created_at) "
                + "VALUES ('evt-edge-09-new', 'apr_edge0000009b', 'ORD-EDGE-09', 10.00, 'USD', 'EXECUTED', 'tx_edge_09b', 'r@x', "
                + "CURRENT_TIMESTAMP)");
        sweeper.sweep(Instant.now());

        assertThat(notifier.getPublishedEvents())
                .filteredOn(e -> "apr_edge00000009".equals(e.approvalId()))
                .extracting(ApprovalWebhookNotifier.ApprovalDecisionEvent::eventId)
                .containsExactly("evt-edge-09-old");
        assertThat(notifier.getPublishedEvents())
                .extracting(ApprovalWebhookNotifier.ApprovalDecisionEvent::approvalId)
                .doesNotContain("apr_edge0000009b");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_event_outbox "
                + "WHERE event_id = 'evt-edge-09-old' AND published_at IS NOT NULL", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_event_outbox "
                + "WHERE event_id = 'evt-edge-09-new' AND published_at IS NULL", Long.class)).isEqualTo(1L);
        jdbc.update("DELETE FROM approval_event_outbox WHERE event_id = 'evt-edge-09-new'");
    }

    @Test
    void approvalEventsCarryAStableEventId() {
        seed("ORD-EDGE-23", "300.00");
        RefundDecision paused = gate.submitRefund(cmd("ORD-EDGE-23", "80.00", "DISPUTE", "edge-23"));
        gate.reject(paused.approvalId(), REVIEWER);
        String stored = jdbc.queryForObject(
                "SELECT event_id FROM approval_event_outbox WHERE approval_id = ?", String.class, paused.approvalId());
        assertThat(stored).isNotBlank();
        assertThat(notifier.getPublishedEvents())
                .filteredOn(e -> paused.approvalId().equals(e.approvalId()))
                .extracting(ApprovalWebhookNotifier.ApprovalDecisionEvent::eventId)
                .containsExactly(stored);
        assertThat(notifier.getPublishedEvents())
                .filteredOn(e -> paused.approvalId().equals(e.approvalId()))
                .extracting(ApprovalWebhookNotifier.ApprovalDecisionEvent::status)
                .containsExactly(ApprovalStatus.REJECTED);
    }

    // ---- Other boundaries ----

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
            assertThat(gate.submitRefund(cmd("ORD-EDGE-17", "45.00", "FEE_DIFF_" + i, "edge-17-" + i)).status())
                    .isEqualTo("EXECUTED");
        }
        jdbc.update("UPDATE refund_approval_order SET created_at = CURRENT_TIMESTAMP - INTERVAL '25' HOUR "
                + "WHERE order_id = 'ORD-EDGE-17'");
        assertThat(gate.submitRefund(cmd("ORD-EDGE-17", "45.00", "FEE_DIFF_5", "edge-17-5")).status())
                .isEqualTo("EXECUTED");
    }

    @Test
    void providerStateGatesConfirmAndRelease() {
        String orderId = "ORD-EDGE-23";
        String key = IdempotencyKeys.next("edge-23");
        seed(orderId, "100.00");
        gateway.simulateTimeoutForOrder(orderId);
        outcome(() -> gate.submitRefund(cmd(orderId, "20.00", "FEE_DIFF", key)));
        String approvalId = approvalIdOf(orderId);
        gateway.clearTimeoutForOrder(orderId);

        gateway.recordProviderState(orderId, key, ProviderRefundLookup.State.PENDING, false,
                "tx_pending", new BigDecimal("20.00"), "USD");
        assertThat(outcomeOf(() -> resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.CONFIRM, "tx_pending")))
                .contains("still processing");

        gateway.recordProviderState(orderId, key, ProviderRefundLookup.State.FAILED, false,
                "tx_failed", new BigDecimal("20.00"), "USD");
        assertThat(outcomeOf(() -> resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.CONFIRM, "tx_failed")))
                .contains("cannot be confirmed");
        gateway.setCancelSupported(false);
        assertThat(outcomeOf(() -> resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.RELEASE, null)))
                .contains("not terminal");
    }

    @Test
    void releaseWithoutCancelWaitsForTheSettlementWindow() {
        String orderId = "ORD-EDGE-24";
        String key = IdempotencyKeys.next("edge-24");
        seed(orderId, "100.00");
        gateway.setCancelSupported(false);
        gateway.simulateTimeoutForOrder(orderId);
        outcome(() -> gate.submitRefund(cmd(orderId, "20.00", "FEE_DIFF", key)));
        String approvalId = approvalIdOf(orderId);

        assertThat(outcomeOf(() -> resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.RELEASE, null)))
                .contains("settlement window");

        jdbc.update("UPDATE refund_approval_order SET created_at = CURRENT_TIMESTAMP - INTERVAL '48' HOUR "
                + "WHERE approval_id = ?", approvalId);
        resolutions.propose(approvalId, REVIEWER, PayoutResolution.Action.RELEASE, null);
        assertThat(resolutions.approve(approvalId, SECOND_REVIEWER).status()).isEqualTo("PAYOUT_RELEASED");
    }

    @Test
    void reconciliationRecordsProviderDriftOnce() {
        String orderId = "ORD-EDGE-25";
        String key = IdempotencyKeys.next("edge-25");
        seed(orderId, "100.00");
        RefundDecision paid = gate.submitRefund(cmd(orderId, "20.00", "FEE_DIFF", key));
        assertThat(paid.status()).isEqualTo("EXECUTED");
        gateway.recordProviderState(orderId, key, ProviderRefundLookup.State.NOT_FOUND, false, null, null, null);

        assertThat(reconciliation.reconcile(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(openMismatches(paid.approvalId())).isEqualTo(1L);
        reconciliation.reconcile(Instant.now());
        assertThat(openMismatches(paid.approvalId())).isEqualTo(1L);

        String releaseOrder = "ORD-EDGE-26";
        String releaseKey = IdempotencyKeys.next("edge-26");
        seed(releaseOrder, "100.00");
        gateway.simulateTimeoutForOrder(releaseOrder);
        outcome(() -> gate.submitRefund(cmd(releaseOrder, "15.00", "FEE_DIFF", releaseKey)));
        String releaseId = approvalIdOf(releaseOrder);
        gateway.clearTimeoutForOrder(releaseOrder);
        resolutions.propose(releaseId, REVIEWER, PayoutResolution.Action.RELEASE, null);
        resolutions.approve(releaseId, SECOND_REVIEWER);
        gateway.recordProviderState(releaseOrder, releaseKey, ProviderRefundLookup.State.SUCCEEDED, true,
                "tx_late", new BigDecimal("15.00"), "USD");
        reconciliation.reconcile(Instant.now());
        assertThat(jdbc.queryForObject(
                "SELECT mismatch_code FROM payout_reconciliation_exception WHERE approval_id = ?",
                String.class, releaseId)).isEqualTo(PayoutReconciliationJob.PROVIDER_SUCCEEDED_AFTER_RELEASE);
    }

    @Test
    void resetCountersDropsRecordedProviderRefunds() {
        String key = IdempotencyKeys.next("edge-27");
        gateway.recordProviderState("ORD-EDGE-27", key, ProviderRefundLookup.State.SUCCEEDED, true,
                "tx_keep", new BigDecimal("10.00"), "USD");
        gateway.resetCounters();
        assertThat(gateway.lookupRefund("ORD-EDGE-27", key).state()).isEqualTo(ProviderRefundLookup.State.NOT_FOUND);
    }

    // ---- Helpers ----

    private int status(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int demoStatus(String orderId, String amount, String reason, String key) throws Exception {
        return status(post("/api/demo/simulate-agent-refund")
                .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(demoBody(orderId, amount, reason, key)));
    }

    private static String demoBody(String orderId, String amount, String reason, String key) {
        return "{\"orderId\":\"" + orderId + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\","
                + "\"reasonCode\":\"" + reason + "\",\"idempotencyKey\":\"" + key + "\"}";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
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

    private Long openMismatches(String approvalId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM payout_reconciliation_exception WHERE open_approval_id = ?",
                Long.class, approvalId);
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

    private static String outcomeOf(Callable<?> call) {
        try {
            return String.valueOf(call.call());
        } catch (Exception e) {
            return "EX:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }
}
