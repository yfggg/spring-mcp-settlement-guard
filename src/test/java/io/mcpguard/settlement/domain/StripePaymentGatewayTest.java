package io.mcpguard.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mcpguard.settlement.config.GuardrailProperties;
import io.mcpguard.settlement.config.StripeProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StripePaymentGatewayTest {

    private HttpServer mockStripe;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        mockStripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockStripe.start();
        baseUrl = "http://127.0.0.1:" + mockStripe.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (mockStripe != null) {
            mockStripe.stop(0);
        }
    }

    @Test
    void executesRefundWithIdempotencyHeaderAndLooksUpByMetadataReference() {
        AtomicReference<String> receivedIdemHeader = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();

        mockStripe.createContext("/v1/refunds", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                receivedIdemHeader.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respondJson(exchange, 200, """
                        {
                          "id": "re_test_8842_01",
                          "amount": 3550,
                          "currency": "usd",
                          "status": "succeeded",
                          "created": 1764150000,
                          "metadata": {
                            "order_id": "ORD-2026-8842",
                            "idempotency_ref": "idem-8842-01"
                          }
                        }
                        """);
            } else {
                respondJson(exchange, 200, """
                        {
                          "object": "list",
                          "data": [
                            {
                              "id": "re_test_8842_01",
                              "amount": 3550,
                              "currency": "usd",
                              "status": "succeeded",
                              "created": 1764150000,
                              "metadata": {
                                "order_id": "ORD-2026-8842",
                                "idempotency_ref": "idem-8842-01"
                              }
                            }
                          ]
                        }
                        """);
            }
        });

        StripePaymentGateway gateway = newGateway("sk_test_mock_123456");
        gateway.registerOrderPaymentIntent("ORD-2026-8842", "pi_test_8842");

        PaymentGatewayPort.PaymentExecutionResult settled = gateway.executeRefund(
                "ORD-2026-8842", new BigDecimal("35.50"), "USD", "idem-8842-01");

        assertThat(settled.transactionId()).isEqualTo("re_test_8842_01");
        assertThat(settled.amount()).isEqualByComparingTo("35.50");
        assertThat(settled.currency()).isEqualTo("USD");
        assertThat(receivedIdemHeader.get()).isEqualTo("mcpguard:ORD-2026-8842:idem-8842-01");
        assertThat(receivedBody.get())
                .contains("payment_intent=pi_test_8842")
                .contains("amount=3550")
                .contains("metadata%5Bidempotency_ref%5D=idem-8842-01");

        ProviderRefundLookup lookup = gateway.lookupRefund("ORD-2026-8842", "idem-8842-01");
        assertThat(lookup.state()).isEqualTo(ProviderRefundLookup.State.SUCCEEDED);
        assertThat(lookup.terminal()).isTrue();
        assertThat(lookup.refund().transactionId()).isEqualTo("re_test_8842_01");

        ProviderRefundLookup missing = gateway.lookupRefund("ORD-2026-8842", "idem-other");
        assertThat(missing.state()).isEqualTo(ProviderRefundLookup.State.NOT_FOUND);
    }

    @Test
    void refusesNonTestKeyWhenSeedingSyntheticDemoCharges() {
        StripePaymentGateway liveKeyGateway = newGateway("sk_live_danger_do_not_seed");
        assertThatThrownBy(() -> liveKeyGateway.ensureDemoCapturedPaymentIntent(
                "ORD-2026-8841", new BigDecimal("300.00"), "USD", "qa@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-test key");
    }

    private StripePaymentGateway newGateway(String key) {
        GuardrailProperties guardrailProps = new GuardrailProperties(
                new GuardrailProperties.Mandate("USD", new BigDecimal("50.00"), new BigDecimal("200.00"), 24),
                new GuardrailProperties.Security(
                        "mcp-demo-agent-key-2026",
                        "mcp-demo-reviewer-jwt-secret-2026-replace-me",
                        "spring-mcp-settlement-guard",
                        "mcp-demo-snapshot-hmac-2026"),
                new GuardrailProperties.Recovery(15, 60000, 30),
                new GuardrailProperties.Demo(true, true),
                new GuardrailProperties.Provider(false, 24, 7, 3_600_000));
        return new StripePaymentGateway(new StripeProperties(key, baseUrl, true), guardrailProps, objectMapper);
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
