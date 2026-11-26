package io.mcpguard.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mcpguard.settlement.config.DemoDataInitializer;
import io.mcpguard.settlement.config.McpSecurityConfig;
import io.mcpguard.settlement.domain.DeterministicPaymentGateway;
import io.mcpguard.settlement.guardrail.ApprovalGateService;
import io.mcpguard.settlement.guardrail.IdempotencyRecordRepository;
import io.mcpguard.settlement.telemetry.ToolExecutionAuditLog;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Zero-API-burn end-to-end MCP wire protocol test using the official MCP Java SDK SyncClient
 * over HTTP + SSE. Verifies handshake, tool schema discovery, deterministic BigDecimal reconciliation,
 * two-phase refund interception, human sign-off, and asynchronous status polling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeterministicMockAgentTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApprovalGateService approvalGateService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private DeterministicPaymentGateway paymentGateway;

    @Autowired
    private ToolExecutionAuditLog auditLog;

    @Test
    void fullMcpProtocolHandshakeReconciliationPauseApproveAndStatusLoop() throws Exception {
        try (McpSyncClient mcpClient = newAuthenticatedClient()) {

            McpSchema.InitializeResult initResult = mcpClient.initialize();
            assertThat(initResult).isNotNull();

            // 1. Discover registered MCP tools & verify schemas
            McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
            assertThat(toolsResult.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder(
                            "reconcile_settlement_batch",
                            "issue_customer_refund",
                            "get_approval_status"
                    );

            // 2. Deterministic read-only batch reconciliation (0 LLM math)
            McpSchema.CallToolResult reconCall = mcpClient.callTool(new McpSchema.CallToolRequest(
                    "reconcile_settlement_batch",
                    Map.of("batchId", DemoDataInitializer.DEMO_BATCH_ID)
            ));
            assertThat(reconCall.isError()).isNotEqualTo(Boolean.TRUE);
            JsonNode reconJson = extractFirstTextAsJson(reconCall);
            assertThat(reconJson.get("batchId").asText()).isEqualTo(DemoDataInitializer.DEMO_BATCH_ID);
            assertThat(reconJson.get("discrepancyCount").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(reconJson.toString()).doesNotContain("customerEmail").doesNotContain("@");

            // 3. Agent proposes $180.00 refund for ORD-2026-8841 -> paused by local $50 mandate
            McpSchema.CallToolResult refundCall = mcpClient.callTool(new McpSchema.CallToolRequest(
                    "issue_customer_refund",
                    Map.of(
                            "orderId", "ORD-2026-8841",
                            "amount", "180.00",
                            "currency", "USD",
                            "reasonCode", "DUPLICATE_CAPTURE",
                            "idempotencyKey", "mcp-wire-8841-01"
                    )
            ));
            JsonNode refundJson = extractFirstTextAsJson(refundCall);
            assertThat(refundJson.get("status").asText()).isEqualTo("PENDING_APPROVAL");
            String approvalId = refundJson.get("approvalId").asText();
            assertThat(approvalId).startsWith("apr_");

            // 4. Out-of-band human finance reviewer approves the paused snapshot
            ApprovalGateService.RefundDecision settled = approvalGateService.approve(
                    approvalId, "cfo@company.com");
            assertThat(settled.status()).isEqualTo("EXECUTED");
            assertThat(settled.refundTxId()).startsWith("tx_");

            // 5. Agent later polls get_approval_status over MCP to close the customer loop
            McpSchema.CallToolResult statusCall = mcpClient.callTool(new McpSchema.CallToolRequest(
                    "get_approval_status",
                    Map.of("approvalId", approvalId)
            ));
            JsonNode statusJson = extractFirstTextAsJson(statusCall);
            assertThat(statusJson.get("status").asText()).isEqualTo("EXECUTED");
            assertThat(statusJson.get("refundTxId").asText()).isEqualTo(settled.refundTxId());
            assertThat(statusJson.has("reviewerId"))
                    .as("finance reviewer identity must not be exposed to agents")
                    .isFalse();
            assertThat(statusJson.has("internalRuleCode")).isFalse();
            assertThat(statusJson.has("rolling24hUsed")).isFalse();
            assertThat(statusJson.has("reasonCode")).isFalse();

            // 6. Reconciliation audit rows are keyed by batch, not misfiled under order_id
            assertThat(auditLog.findByBatchId(DemoDataInitializer.DEMO_BATCH_ID))
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row.getOrderId()).isNull());
        }
    }

    @Test
    void rejectsMalformedToolArgumentsBeforeClaimingIdempotencyOrTouchingPayments() {
        String orderId = "ORD-2026-8842";
        int paymentsBefore = paymentGateway.getExecutionCountForOrder(orderId);

        try (McpSyncClient mcpClient = newAuthenticatedClient()) {
            mcpClient.initialize();

            Map<String, Map<String, Object>> malformedCalls = Map.of(
                    "mcp-bad-amount-precision", refundArgs(orderId, "45.005", "USD", "mcp-bad-amount-precision"),
                    "mcp-bad-amount-text", refundArgs(orderId, "forty five", "USD", "mcp-bad-amount-text"),
                    "mcp-bad-amount-negative", refundArgs(orderId, "-10.00", "USD", "mcp-bad-amount-negative"),
                    "mcp-bad-currency-code", refundArgs(orderId, "10.00", "US Dollars", "mcp-bad-currency-code"),
                    "mcp-bad-order-injection", refundArgs("ORD-1; DROP TABLE x", "10.00", "USD", "mcp-bad-order-injection")
            );

            malformedCalls.forEach((idempotencyKey, args) -> {
                McpSchema.CallToolResult result = mcpClient.callTool(
                        new McpSchema.CallToolRequest("issue_customer_refund", args));
                assertThat(result.isError())
                        .as("malformed call %s must be rejected", idempotencyKey)
                        .isTrue();
                assertThat(idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey))
                        .as("no idempotency slot may be claimed for %s", idempotencyKey)
                        .isEmpty();
            });

            McpSchema.CallToolResult badStatusLookup = mcpClient.callTool(new McpSchema.CallToolRequest(
                    "get_approval_status",
                    Map.of("approvalId", "' OR 1=1 --")
            ));
            assertThat(badStatusLookup.isError()).isTrue();
        }

        assertThat(paymentGateway.getExecutionCountForOrder(orderId)).isEqualTo(paymentsBefore);
    }

    private McpSyncClient newAuthenticatedClient() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://localhost:" + port)
                .sseEndpoint("/sse")
                .customizeRequest(req -> req.header(McpSecurityConfig.HEADER_AGENT_API_KEY, "test-agent-key"))
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .build();
    }

    private static Map<String, Object> refundArgs(String orderId, String amount, String currency, String idempotencyKey) {
        return Map.of(
                "orderId", orderId,
                "amount", amount,
                "currency", currency,
                "reasonCode", "DUPLICATE_CHARGE",
                "idempotencyKey", idempotencyKey
        );
    }

    private JsonNode extractFirstTextAsJson(McpSchema.CallToolResult result) throws Exception {
        assertThat(result.content()).isNotEmpty();
        McpSchema.Content first = result.content().get(0);
        assertThat(first).isInstanceOf(McpSchema.TextContent.class);
        String text = ((McpSchema.TextContent) first).text();
        return objectMapper.readTree(text);
    }
}
