package io.mcpguard.settlement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.mcpguard.settlement.config.McpSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** With guardrail.demo.http-enabled=false the quick-start refund endpoint is not registered at all. */
@SpringBootTest(properties = "guardrail.demo.http-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoEndpointDisabledIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void demoRefundEndpointReturns404WhenDisabled() throws Exception {
        mockMvc.perform(post("/api/demo/simulate-agent-refund")
                        .header(McpSecurityConfig.HEADER_AGENT_API_KEY, "test-agent-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-2026-8842\",\"amount\":\"10.00\",\"currency\":\"USD\","
                                + "\"reasonCode\":\"FEE_DIFF\",\"idempotencyKey\":\"demo-off-01\"}"))
                .andExpect(status().isNotFound());
    }
}
