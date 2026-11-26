package io.mcpguard.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mcpguard.settlement.config.McpSecurityConfig;
import io.mcpguard.settlement.domain.DeterministicPaymentGateway;
import io.mcpguard.settlement.domain.SettlementInvoiceItem;
import io.mcpguard.settlement.domain.SnapshotHasher;
import io.mcpguard.settlement.domain.SettlementInvoiceRepository;
import io.mcpguard.settlement.guardrail.ApprovalGateService;
import io.mcpguard.settlement.guardrail.ApprovalStatus;
import io.mcpguard.settlement.guardrail.ApprovalWebhookNotifier;
import io.mcpguard.settlement.guardrail.GuardrailExceptions;
import io.mcpguard.settlement.guardrail.IdempotencyKeyStore;
import io.mcpguard.settlement.guardrail.IdempotencyRecord;
import io.mcpguard.settlement.guardrail.IdempotencyRecordRepository;
import io.mcpguard.settlement.guardrail.RefundApprovalOrder;
import io.mcpguard.settlement.guardrail.RefundApprovalOrderRepository;
import io.mcpguard.settlement.guardrail.StaleExecutionSweeper;
import io.mcpguard.settlement.telemetry.AuditLogRecord;
import io.mcpguard.settlement.telemetry.ToolExecutionAuditLog;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalLifecycleIT {

    private static final String AGENT_KEY = "test-agent-key";
    private static final String REVIEWER_KEY = "test-reviewer-key";
    private static final String REVIEWER_EMAIL = "finance-lead@company.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApprovalGateService approvalGateService;

    @Autowired
    private DeterministicPaymentGateway paymentGateway;

    @Autowired
    private ApprovalWebhookNotifier webhookNotifier;

    @Autowired
    private ToolExecutionAuditLog auditLog;

    @Autowired
    private RefundApprovalOrderRepository approvalRepository;

    @Autowired
    private SettlementInvoiceRepository invoiceRepository;

    @Autowired
    private IdempotencyKeyStore idempotencyKeyStore;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private StaleExecutionSweeper staleExecutionSweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetCountersAndSeedTestOrders() {
        paymentGateway.resetCounters();
        webhookNotifier.clearEvents();
        seedInvoice("ORD-CONCURRENT-RACE-01", "500.00");
        seedInvoice("ORD-IDEM-CHECK-01", "300.00");
        seedInvoice("ORD-SEC-01", "300.00");
        seedInvoice("ORD-BALANCE-01", "100.00");
        seedInvoice("ORD-TIMEOUT-AUTO-01", "100.00");
        seedInvoice("ORD-TIMEOUT-APPROVE-01", "300.00");
        seedInvoice("ORD-REJECT-RACE-01", "300.00");
        seedInvoice("ORD-STALE-01", "300.00");
        seedInvoice("ORD-PAUSE-01", "100.00");
        seedInvoice("ORD-RECEIPT-01", "100.00");
        seedInvoice("ORD-CONFIRM-01", "100.00");
        seedInvoice("ORD-RELEASE-01", "100.00");
    }

    @Test
    void autoExecutesLowRiskRefundAtOrBelow50AndLogsZeroTokenAudit() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "orderId", "ORD-2026-8842",
                "amount", "35.50",
                "currency", "USD",
                "reasonCode", "SHIPPING_OVERCHARGE",
                "idempotencyKey", "idem-low-8842-01"
        ));

        mockMvc.perform(post("/api/demo/simulate-agent-refund")
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.refundTxId").isNotEmpty());

        assertThat(paymentGateway.getExecutionCountForOrder("ORD-2026-8842")).isEqualTo(1);
        assertThat(invoiceRepository.findByOrderId("ORD-2026-8842").orElseThrow().getAlreadyRefundedAmount())
                .isEqualByComparingTo("35.50");

        List<AuditLogRecord> logs = auditLog.findByOrderId("ORD-2026-8842");
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).getInternalPolicyRule()).isEqualTo(ApprovalGateService.RULE_AUTO_UNDER_MANDATE);
        assertThat(logs.get(0).getLlmTokensBurned()).isZero();
    }

    @Test
    void pausesRefundAbove50UntilHumanApprovalAndPublishesWebhook() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "orderId", "ORD-2026-8841",
                "amount", "180.00",
                "currency", "USD",
                "reasonCode", "DUPLICATE_CHARGE",
                "idempotencyKey", "idem-high-8841-01"
        ));

        MvcResult pausedRes = mockMvc.perform(post("/api/demo/simulate-agent-refund")
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.refundTxId").isEmpty())
                .andReturn();

        ApprovalGateService.RefundDecision paused = objectMapper.readValue(
                pausedRes.getResponse().getContentAsString(),
                ApprovalGateService.RefundDecision.class
        );
        assertThat(paymentGateway.getExecutionCountForOrder("ORD-2026-8841")).isZero();
        assertThat(paused.message()).doesNotContain("50").doesNotContain("200");

        mockMvc.perform(get("/api/approvals/{id}", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("DUPLICATE_CHARGE"))
                .andExpect(jsonPath("$.internalRuleCode").value(ApprovalGateService.RULE_SINGLE_LIMIT))
                .andExpect(jsonPath("$.rolling24hUsed").value(180.0))
                .andExpect(jsonPath("$.refundableAmount").value(120.0));

        MvcResult approvedRes = mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.refundTxId").isNotEmpty())
                .andReturn();
        ApprovalGateService.RefundDecision approved = objectMapper.readValue(
                approvedRes.getResponse().getContentAsString(),
                ApprovalGateService.RefundDecision.class);

        ApprovalGateService.RefundDecision replay = approvalGateService.submitRefund(
                refund("ORD-2026-8841", "180.00", "DUPLICATE_CHARGE", "idem-high-8841-01"));
        assertThat(replay.status()).isEqualTo("EXECUTED");
        assertThat(replay.refundTxId()).isEqualTo(approved.refundTxId());
        assertThat(paymentGateway.getExecutionCountForOrder("ORD-2026-8841")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM approval_event_outbox WHERE approval_id = ? AND published_at IS NOT NULL",
                Long.class,
                paused.approvalId())).isEqualTo(1L);
        assertThat(webhookNotifier.getPublishedEvents())
                .extracting(ApprovalWebhookNotifier.ApprovalDecisionEvent::approvalId)
                .contains(paused.approvalId());
    }

    @Test
    void blocksSmurfingSplitClaimsExceeding200In24hWithOpaqueMessageAndReleasesOnReject() throws Exception {
        String orderId = "ORD-2026-9900";

        // 4 x $45.00 = $180.00 <= $200.00 rolling 24h cap -> all 4 auto-execute
        for (int i = 1; i <= 4; i++) {
            ApprovalGateService.RefundDecision res = approvalGateService.submitRefund(
                    refund(orderId, "45.00", "PARTIAL_ADJUSTMENT", "idem-smurf-" + i));
            assertThat(res.status()).isEqualTo("EXECUTED");
        }
        assertThat(paymentGateway.getExecutionCountForOrder(orderId)).isEqualTo(4);

        // 5th x $45.00 pushes rolling 24h total to $225.00 > $200.00 -> paused for human review
        ApprovalGateService.RefundDecision fifth = approvalGateService.submitRefund(
                refund(orderId, "45.00", "PARTIAL_ADJUSTMENT", "idem-smurf-5"));
        assertThat(fifth.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(fifth.message())
                .isEqualTo(ApprovalGateService.OPAQUE_HOLD_MESSAGE)
                .doesNotContain("50")
                .doesNotContain("200")
                .doesNotContain("225");
        assertThat(paymentGateway.getExecutionCountForOrder(orderId)).isEqualTo(4);

        // Internal audit trail still records the exact velocity rule for enterprise finance compliance
        assertThat(auditLog.findByOrderId(orderId))
                .extracting(AuditLogRecord::getInternalPolicyRule)
                .contains(ApprovalGateService.RULE_ROLLING_24H_VELOCITY);

        // Rejecting the 5th claim releases its $45 in-flight reservation so a $20 micro-refund ($180 + $20 <= $200) succeeds
        mockMvc.perform(post("/api/approvals/{id}/reject", fifth.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        ApprovalGateService.RefundDecision microAfterReject = approvalGateService.submitRefund(
                refund(orderId, "20.00", "SHIPPING_DIFF", "idem-smurf-after-reject"));
        assertThat(microAfterReject.status()).isEqualTo("EXECUTED");
        assertThat(paymentGateway.getExecutionCountForOrder(orderId)).isEqualTo(5);
    }

    @Test
    void preventsConcurrentVelocityRaceAndConcurrentApprovalDoublePayout() throws Exception {
        String raceOrderId = "ORD-CONCURRENT-RACE-01";
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        // Part A: 10 threads simultaneously request $45.00 on the same order, each a distinct refund line.
        // Without SELECT ... FOR UPDATE, all 10 would see $0 used and pay out $450.00.
        // With pessimistic row locking, strictly 4 ($180.00 <= $200.00) execute and 6 pause.
        // Distinct reason codes keep these from being merged as retries of one in-flight refund.
        List<Future<ApprovalGateService.RefundDecision>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<ApprovalGateService.RefundDecision>) () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return approvalGateService.submitRefund(
                        refund(raceOrderId, "45.00", "CONCURRENT_CLAIM_" + idx, "idem-race-" + idx));
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        int executedCount = 0;
        int pausedCount = 0;
        String onePausedApprovalId = null;
        for (Future<ApprovalGateService.RefundDecision> f : futures) {
            ApprovalGateService.RefundDecision d = f.get(10, TimeUnit.SECONDS);
            if ("EXECUTED".equals(d.status())) {
                executedCount++;
            } else if ("PENDING_APPROVAL".equals(d.status())) {
                pausedCount++;
                onePausedApprovalId = d.approvalId();
            }
        }

        assertThat(executedCount).isEqualTo(4);
        assertThat(pausedCount).isEqualTo(6);
        assertThat(paymentGateway.getExecutionCountForOrder(raceOrderId)).isEqualTo(4);

        // Part B: 10 finance threads click approve on the same paused approvalId at once.
        // Exactly one wins the CAS; the rest see EXECUTING (409) or EXECUTED (already executed).
        CountDownLatch approveReady = new CountDownLatch(threads);
        CountDownLatch approveStart = new CountDownLatch(1);
        final String targetApprovalId = onePausedApprovalId;
        List<Future<ApprovalGateService.RefundDecision>> approveFutures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            approveFutures.add(pool.submit(() -> {
                approveReady.countDown();
                approveStart.await(5, TimeUnit.SECONDS);
                return approvalGateService.approve(targetApprovalId, REVIEWER_EMAIL);
            }));
        }
        approveReady.await(5, TimeUnit.SECONDS);
        approveStart.countDown();

        int approvedResponses = 0;
        for (Future<ApprovalGateService.RefundDecision> f : approveFutures) {
            try {
                assertThat(f.get(10, TimeUnit.SECONDS).status()).isEqualTo("EXECUTED");
                approvedResponses++;
            } catch (ExecutionException ex) {
                assertThat(ex.getCause()).isInstanceOf(GuardrailExceptions.ApprovalStateConflictException.class);
            }
        }
        pool.shutdownNow();

        // 4 auto-executed + strictly 1 human-approved = 5 total executions
        assertThat(approvedResponses).isGreaterThanOrEqualTo(1);
        assertThat(paymentGateway.getExecutionCountForOrder(raceOrderId)).isEqualTo(5);
        assertThat(approvalRepository.findByApprovalId(targetApprovalId).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXECUTED);
        assertThat(invoiceRepository.findByOrderId(raceOrderId).orElseThrow().getAlreadyRefundedAmount())
                .isEqualByComparingTo("225.00");
    }

    @Test
    void enforcesIdempotentRetriesAndRejectsPayloadOrSnapshotTampering() throws Exception {
        String orderId = "ORD-IDEM-CHECK-01";
        ApprovalGateService.RefundDecision first = approvalGateService.submitRefund(
                refund(orderId, "25.00", "FEE_DIFF", "idem-fixed-01"));

        for (int i = 0; i < 4; i++) {
            ApprovalGateService.RefundDecision retry = approvalGateService.submitRefund(
                    refund(orderId, "25.00", "FEE_DIFF", "idem-fixed-01"));
            assertThat(retry.refundTxId()).isEqualTo(first.refundTxId());
        }
        assertThat(paymentGateway.getExecutionCountForOrder(orderId)).isEqualTo(1);

        // Same key, same amount, different reason code -> the fingerprint covers every business field
        assertThatThrownBy(() -> approvalGateService.submitRefund(
                refund(orderId, "25.00", "GOODWILL_CREDIT", "idem-fixed-01")))
                .isInstanceOf(GuardrailExceptions.IdempotencyConflictException.class);

        // Reusing same idempotencyKey with a modified amount ($25.00 -> $49.00) must return 409 Conflict
        String tamperedRetryPayload = objectMapper.writeValueAsString(Map.of(
                "orderId", orderId,
                "amount", "49.00",
                "currency", "USD",
                "reasonCode", "FEE_DIFF",
                "idempotencyKey", "idem-fixed-01"
        ));
        mockMvc.perform(post("/api/demo/simulate-agent-refund")
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedRetryPayload))
                .andExpect(status().isConflict());

        // Create a paused high-value approval, tamper with its persisted DB amount, and verify SHA-256 blocks payout
        ApprovalGateService.RefundDecision paused = approvalGateService.submitRefund(
                refund(orderId, "120.00", "DISPUTE", "idem-tamper-db-01"));
        assertThat(paused.status()).isEqualTo(ApprovalStatus.PENDING_APPROVAL.name());

        jdbcTemplate.update(
                "UPDATE refund_approval_order SET amount = 999.00 WHERE approval_id = ?",
                paused.approvalId());

        mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SNAPSHOT_INTEGRITY_VIOLATION"));

        jdbcTemplate.update(
                "UPDATE refund_approval_order SET amount = 999.00, snapshot_hash = ? WHERE approval_id = ?",
                unsignedSha256(orderId + "|999.00|USD|DISPUTE"),
                paused.approvalId());
        mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SNAPSHOT_INTEGRITY_VIOLATION"));

        // Still only the original $25.00 execution ran; tampered $999.00 never touched the payment gateway
        assertThat(paymentGateway.getExecutionCountForOrder(orderId)).isEqualTo(1);
    }

    @Test
    void rejectsUnknownOrdersAndRefundsBeyondRefundableBalanceWithoutBurningTheKey() throws Exception {
        // Unknown order: rejected before any reservation, idempotency slot released
        assertThatThrownBy(() -> approvalGateService.submitRefund(
                refund("ORD-DOES-NOT-EXIST", "10.00", "FEE_DIFF", "idem-unknown-01")))
                .isInstanceOf(GuardrailExceptions.InvalidSettlementRequestException.class);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey("idem-unknown-01")).isEmpty();
        assertThat(paymentGateway.getExecutionCountForOrder("ORD-DOES-NOT-EXIST")).isZero();

        mockMvc.perform(post("/api/demo/simulate-agent-refund")
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderId", "ORD-DOES-NOT-EXIST",
                                "amount", "10.00",
                                "currency", "USD",
                                "reasonCode", "FEE_DIFF",
                                "idempotencyKey", "idem-unknown-02"))))
                .andExpect(status().isBadRequest());

        // Captured $100.00: $40 auto-executes, leaving $60 refundable
        String orderId = "ORD-BALANCE-01";
        assertThat(approvalGateService.submitRefund(refund(orderId, "40.00", "FEE_DIFF", "idem-balance-01")).status())
                .isEqualTo("EXECUTED");

        assertThatThrownBy(() -> approvalGateService.submitRefund(
                refund(orderId, "70.00", "FEE_DIFF", "idem-balance-02")))
                .isInstanceOf(GuardrailExceptions.InvalidSettlementRequestException.class);

        // The rejected key was released, so the agent can correct the amount and reuse it. $60 > $50 -> paused.
        ApprovalGateService.RefundDecision corrected = approvalGateService.submitRefund(
                refund(orderId, "60.00", "FEE_DIFF", "idem-balance-02"));
        assertThat(corrected.status()).isEqualTo("PENDING_APPROVAL");

        // The pending $60 is reserved against the balance, so not even $1.00 more is accepted
        assertThatThrownBy(() -> approvalGateService.submitRefund(
                refund(orderId, "1.00", "FEE_DIFF", "idem-balance-03")))
                .isInstanceOf(GuardrailExceptions.InvalidSettlementRequestException.class);
        assertThat(paymentGateway.getExecutionCountForOrder(orderId)).isEqualTo(1);
    }

    @Test
    void freezesPayoutAsUnknownOnGatewayTimeoutAndBlocksEveryRetryPath() throws Exception {
        // Auto-approved path: gateway times out after the reservation committed
        String autoOrder = "ORD-TIMEOUT-AUTO-01";
        paymentGateway.simulateTimeoutForOrder(autoOrder);

        assertThatThrownBy(() -> approvalGateService.submitRefund(
                refund(autoOrder, "20.00", "FEE_DIFF", "idem-timeout-auto-01")))
                .isInstanceOf(GuardrailExceptions.PayoutOutcomeUnknownException.class);

        assertThat(idempotencyRecordRepository.findByIdempotencyKey("idem-timeout-auto-01").orElseThrow().getState())
                .isEqualTo(IdempotencyRecord.State.UNKNOWN_SUSPENDED);
        assertThat(statusesForOrder(autoOrder)).containsExactly(ApprovalStatus.PAYOUT_UNKNOWN.name());
        ApprovalGateService.RefundDecision frozenReplay = approvalGateService.submitRefund(
                refund(autoOrder, "20.00", "FEE_DIFF", "idem-timeout-auto-01"));
        assertThat(frozenReplay.status()).isEqualTo(ApprovalStatus.PAYOUT_UNKNOWN.name());
        ApprovalGateService.RefundDecision newKey = approvalGateService.submitRefund(
                refund(autoOrder, "20.00", "FEE_DIFF", "idem-timeout-auto-02"));
        assertThat(newKey.approvalId()).isEqualTo(frozenReplay.approvalId());
        assertThat(newKey.status()).isEqualTo(ApprovalStatus.PAYOUT_UNKNOWN.name());
        assertThat(paymentGateway.getExecutionCountForOrder(autoOrder)).isZero();
        assertThat(statusesForOrder(autoOrder)).containsExactly(ApprovalStatus.PAYOUT_UNKNOWN.name());

        // Human-approved path: approve hits a gateway timeout -> 502, frozen, and a second click cannot pay again
        String approveOrder = "ORD-TIMEOUT-APPROVE-01";
        ApprovalGateService.RefundDecision paused = approvalGateService.submitRefund(
                refund(approveOrder, "120.00", "DISPUTE", "idem-timeout-approve-01"));
        paymentGateway.simulateTimeoutForOrder(approveOrder);

        mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("PAYOUT_OUTCOME_UNKNOWN"));

        assertThat(approvalRepository.findByApprovalId(paused.approvalId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PAYOUT_UNKNOWN);

        mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isConflict());

        ApprovalGateService.RefundDecision frozenAfterApprove = approvalGateService.submitRefund(
                refund(approveOrder, "120.00", "DISPUTE", "idem-timeout-approve-01"));
        assertThat(frozenAfterApprove.status()).isEqualTo(ApprovalStatus.PAYOUT_UNKNOWN.name());
        assertThat(paymentGateway.getExecutionCountForOrder(approveOrder)).isZero();
        assertThat(invoiceRepository.findByOrderId(approveOrder).orElseThrow().getAlreadyRefundedAmount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void rejectNeverReportsARejectionThatDidNotHappen() throws Exception {
        String orderId = "ORD-REJECT-RACE-01";

        // Approval wins first -> a late reject is a 409 and publishes no REJECTED event
        ApprovalGateService.RefundDecision approvedFirst = approvalGateService.submitRefund(
                refund(orderId, "90.00", "DISPUTE", "idem-reject-race-01"));
        approvalGateService.approve(approvedFirst.approvalId(), REVIEWER_EMAIL);

        mockMvc.perform(post("/api/approvals/{id}/reject", approvedFirst.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, "second-reviewer@company.com"))
                .andExpect(status().isConflict());

        // Expired request -> reject is a 409, status becomes EXPIRED
        ApprovalGateService.RefundDecision expiring = approvalGateService.submitRefund(
                refund(orderId, "80.00", "DISPUTE", "idem-reject-race-02"));
        jdbcTemplate.update(
                "UPDATE refund_approval_order SET expires_at = CURRENT_TIMESTAMP - INTERVAL '2' HOUR WHERE approval_id = ?",
                expiring.approvalId());

        mockMvc.perform(post("/api/approvals/{id}/reject", expiring.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isConflict());
        assertThat(approvalRepository.findByApprovalId(expiring.approvalId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);

        // Double reject is idempotent: one REJECTED event, second call returns the same state
        ApprovalGateService.RefundDecision rejectTwice = approvalGateService.submitRefund(
                refund(orderId, "60.00", "DISPUTE", "idem-reject-race-03"));
        approvalGateService.reject(rejectTwice.approvalId(), REVIEWER_EMAIL);
        assertThat(approvalGateService.reject(rejectTwice.approvalId(), REVIEWER_EMAIL).status())
                .isEqualTo("REJECTED");
        assertThat(approvalGateService.submitRefund(
                refund(orderId, "60.00", "DISPUTE", "idem-reject-race-03")).status())
                .isEqualTo("REJECTED");

        List<ApprovalWebhookNotifier.ApprovalDecisionEvent> events = webhookNotifier.getPublishedEvents();
        assertThat(events)
                .filteredOn(e -> e.status() == ApprovalStatus.REJECTED)
                .extracting(ApprovalWebhookNotifier.ApprovalDecisionEvent::approvalId)
                .containsExactly(rejectTwice.approvalId());
    }

    @Test
    void enforcesServerSideRolesAndRejectsExpiredApprovals() throws Exception {
        // 1. Unauthenticated request -> 401
        mockMvc.perform(get("/api/approvals/apr_nonexistent"))
                .andExpect(status().isUnauthorized());

        // 2. Pause an action, then verify an MCP agent key CANNOT self-approve it -> 403 Forbidden
        ApprovalGateService.RefundDecision paused = approvalGateService.submitRefund(
                refund("ORD-SEC-01", "90.00", "DISPUTE", "idem-sec-01"));

        mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, "rogue-agent@bot.local"))
                .andExpect(status().isForbidden());

        // 3. Reviewer key without a reviewer identity is not authenticated -> 401
        mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY))
                .andExpect(status().isUnauthorized());

        // 4. Roles come from the matched secret only: a reviewer cannot act as an agent, whatever headers it sends
        mockMvc.perform(post("/api/demo/simulate-agent-refund")
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL)
                        .header("X-Reviewer-Role", "MCP_AGENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderId", "ORD-SEC-01",
                                "amount", "10.00",
                                "currency", "USD",
                                "reasonCode", "FEE_DIFF",
                                "idempotencyKey", "idem-sec-02"))))
                .andExpect(status().isForbidden());

        // 5. Force expiration timestamp into the past -> approval rejected with 409 Conflict and status transitions to EXPIRED
        jdbcTemplate.update(
                "UPDATE refund_approval_order SET expires_at = CURRENT_TIMESTAMP - INTERVAL '2' HOUR WHERE approval_id = ?",
                paused.approvalId());

        mockMvc.perform(post("/api/approvals/{id}/approve", paused.approvalId())
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isConflict());

        assertThat(approvalRepository.findByApprovalId(paused.approvalId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approvalGateService.submitRefund(
                refund("ORD-SEC-01", "90.00", "DISPUTE", "idem-sec-01")).status())
                .isEqualTo("EXPIRED");
        assertThat(paymentGateway.getExecutionCountForOrder("ORD-SEC-01")).isZero();
    }

    @Test
    void sweeperFreezesRequestsLeftInFlightByACrash() {
        Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));

        // Idempotency key claimed but never completed
        idempotencyKeyStore.tryClaimOrReplay("idem-stale-01", "0".repeat(64));
        jdbcTemplate.update(
                "UPDATE idempotency_record SET updated_at = CURRENT_TIMESTAMP - INTERVAL '1' HOUR WHERE idempotency_key = ?",
                "idem-stale-01");

        // Payout reserved as EXECUTING but never reported back
        approvalRepository.save(new RefundApprovalOrder(
                "apr_stale0000001",
                "ORD-STALE-01",
                new BigDecimal("30.00"),
                "USD",
                "FEE_DIFF",
                "idem-stale-02",
                SnapshotHasher.hash("test-snapshot-hmac", "ORD-STALE-01", new BigDecimal("30.00"), "USD", "FEE_DIFF"),
                ApprovalStatus.EXECUTING,
                ApprovalGateService.RULE_AUTO_UNDER_MANDATE,
                null,
                oneHourAgo,
                oneHourAgo.plus(Duration.ofHours(24))
        ));

        StaleExecutionSweeper.SweepResult result = staleExecutionSweeper.sweep(Instant.now());

        assertThat(result.suspendedIdempotencyKeys()).isGreaterThanOrEqualTo(1);
        assertThat(result.frozenPayouts()).isGreaterThanOrEqualTo(1);
        assertThat(idempotencyRecordRepository.findByIdempotencyKey("idem-stale-01").orElseThrow().getState())
                .isEqualTo(IdempotencyRecord.State.UNKNOWN_SUSPENDED);
        assertThat(approvalRepository.findByApprovalId("apr_stale0000001").orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PAYOUT_UNKNOWN);
    }

    @Test
    void completedReceiptSurvivesALaterUnknownSuspension() {
        ApprovalGateService.RefundDecision first = approvalGateService.submitRefund(
                refund("ORD-RECEIPT-01", "25.00", "FEE_DIFF", "idem-receipt-01"));
        idempotencyKeyStore.markUnknownSuspended("idem-receipt-01", "{\"status\":\"UNKNOWN_SUSPENDED\"}");

        assertThat(idempotencyRecordRepository.findByIdempotencyKey("idem-receipt-01").orElseThrow().getState())
                .isEqualTo(IdempotencyRecord.State.COMPLETED);
        ApprovalGateService.RefundDecision replay = approvalGateService.submitRefund(
                refund("ORD-RECEIPT-01", "25.00", "FEE_DIFF", "idem-receipt-01"));
        assertThat(replay.refundTxId()).isEqualTo(first.refundTxId());
        assertThat(paymentGateway.getExecutionCountForOrder("ORD-RECEIPT-01")).isEqualTo(1);
    }

    @Test
    void inProgressRetryKeepsTheSameKey() throws Exception {
        ApprovalGateService.RefundCommand command = refund("ORD-INPROGRESS-01", "15.00", "FEE_DIFF", "idem-inflight-01");
        idempotencyKeyStore.tryClaimOrReplay("idem-inflight-01", ApprovalGateService.computeRequestFingerprint(command));

        mockMvc.perform(post("/api/demo/simulate-agent-refund")
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderId", "ORD-INPROGRESS-01",
                                "amount", "15.00",
                                "currency", "USD",
                                "reasonCode", "FEE_DIFF",
                                "idempotencyKey", "idem-inflight-01"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("same idempotency key")));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        paymentGateway.pauseNextExecution(entered, release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalGateService.RefundDecision> first = pool.submit(() -> approvalGateService.submitRefund(
                    refund("ORD-PAUSE-01", "20.00", "FEE_DIFF", "idem-pause-01")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            ApprovalGateService.RefundDecision second = approvalGateService.submitRefund(
                    refund("ORD-PAUSE-01", "20.00", "FEE_DIFF", "idem-pause-01"));
            assertThat(second.status()).isEqualTo(ApprovalStatus.EXECUTING.name());
            assertThat(second.message()).contains("same idempotency key");
            assertThat(paymentGateway.getExecutionCountForOrder("ORD-PAUSE-01")).isZero();

            release.countDown();
            ApprovalGateService.RefundDecision settled = first.get(10, TimeUnit.SECONDS);
            assertThat(settled.status()).isEqualTo("EXECUTED");
            ApprovalGateService.RefundDecision replay = approvalGateService.submitRefund(
                    refund("ORD-PAUSE-01", "20.00", "FEE_DIFF", "idem-pause-01"));
            assertThat(replay.refundTxId()).isEqualTo(settled.refundTxId());
            assertThat(paymentGateway.getExecutionCountForOrder("ORD-PAUSE-01")).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void financeCanConfirmOrReleaseAFrozenPayout() throws Exception {
        String confirmOrder = "ORD-CONFIRM-01";
        paymentGateway.simulateTimeoutForOrder(confirmOrder);
        assertThatThrownBy(() -> approvalGateService.submitRefund(
                refund(confirmOrder, "20.00", "FEE_DIFF", "idem-confirm-01")))
                .isInstanceOf(GuardrailExceptions.PayoutOutcomeUnknownException.class);
        String confirmApprovalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM refund_approval_order WHERE order_id = ?",
                String.class,
                confirmOrder);

        mockMvc.perform(post("/api/approvals/{id}/confirm-payout", confirmApprovalId)
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, AGENT_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, "rogue-agent@bot.local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"tx_manual_confirm_01\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/approvals/{id}/confirm-payout", confirmApprovalId)
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"tx_manual_confirm_01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.refundTxId").value("tx_manual_confirm_01"));

        mockMvc.perform(post("/api/approvals/{id}/confirm-payout", confirmApprovalId)
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"tx_manual_confirm_01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertThat(invoiceRepository.findByOrderId(confirmOrder).orElseThrow().getAlreadyRefundedAmount())
                .isEqualByComparingTo("20.00");
        assertThat(paymentGateway.getExecutionCountForOrder(confirmOrder)).isZero();
        ApprovalGateService.RefundDecision confirmedReplay = approvalGateService.submitRefund(
                refund(confirmOrder, "20.00", "FEE_DIFF", "idem-confirm-01"));
        assertThat(confirmedReplay.status()).isEqualTo("EXECUTED");
        assertThat(confirmedReplay.refundTxId()).isEqualTo("tx_manual_confirm_01");
        assertThat(paymentGateway.getExecutionCountForOrder(confirmOrder)).isZero();

        jdbcTemplate.update(
                "UPDATE idempotency_record SET state = 'COMPLETED', response_json = ? WHERE idempotency_key = ?",
                objectMapper.writeValueAsString(new ApprovalGateService.RefundDecision(
                        "PAYOUT_UNKNOWN",
                        confirmedReplay.approvalId(),
                        null,
                        confirmOrder,
                        new BigDecimal("20.00"),
                        "USD",
                        "stale cache")),
                "idem-confirm-01");
        ApprovalGateService.RefundDecision healed = approvalGateService.submitRefund(
                refund(confirmOrder, "20.00", "FEE_DIFF", "idem-confirm-01"));
        assertThat(healed.status()).isEqualTo("EXECUTED");
        assertThat(healed.refundTxId()).isEqualTo("tx_manual_confirm_01");
        assertThat(paymentGateway.getExecutionCountForOrder(confirmOrder)).isZero();

        String releaseOrder = "ORD-RELEASE-01";
        paymentGateway.simulateTimeoutForOrder(releaseOrder);
        assertThatThrownBy(() -> approvalGateService.submitRefund(
                refund(releaseOrder, "20.00", "FEE_DIFF", "idem-release-01")))
                .isInstanceOf(GuardrailExceptions.PayoutOutcomeUnknownException.class);
        String releaseApprovalId = jdbcTemplate.queryForObject(
                "SELECT approval_id FROM refund_approval_order WHERE order_id = ?",
                String.class,
                releaseOrder);

        mockMvc.perform(post("/api/approvals/{id}/release-payout", releaseApprovalId)
                        .header(McpSecurityConfig.HEADER_REVIEWER_KEY, REVIEWER_KEY)
                        .header(McpSecurityConfig.HEADER_REVIEWER_ID, REVIEWER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYOUT_RELEASED"));
        assertThat(invoiceRepository.findByOrderId(releaseOrder).orElseThrow().getAlreadyRefundedAmount())
                .isEqualByComparingTo("0.00");

        paymentGateway.clearTimeoutForOrder(releaseOrder);
        ApprovalGateService.RefundDecision retried = approvalGateService.submitRefund(
                refund(releaseOrder, "20.00", "FEE_DIFF", "idem-release-02"));
        assertThat(retried.status()).isEqualTo("EXECUTED");
        assertThat(paymentGateway.getExecutionCountForOrder(releaseOrder)).isEqualTo(1);
        assertThat(invoiceRepository.findByOrderId(releaseOrder).orElseThrow().getAlreadyRefundedAmount())
                .isEqualByComparingTo("20.00");
    }

    private static ApprovalGateService.RefundCommand refund(
            String orderId, String amount, String reasonCode, String idempotencyKey) {
        return new ApprovalGateService.RefundCommand(orderId, new BigDecimal(amount), "USD", reasonCode, idempotencyKey);
    }

    private void seedInvoice(String orderId, String capturedAmount) {
        if (invoiceRepository.findByOrderId(orderId).isEmpty()) {
            invoiceRepository.save(new SettlementInvoiceItem(
                    "BATCH-TEST",
                    orderId,
                    "qa@example.com",
                    "USD",
                    new BigDecimal(capturedAmount),
                    new BigDecimal(capturedAmount),
                    new BigDecimal("0.00")
            ));
        }
    }

    private List<String> statusesForOrder(String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM refund_approval_order WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    private static String unsignedSha256(String canonical) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}
