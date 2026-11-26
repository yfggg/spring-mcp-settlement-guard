package io.mcpguard.settlement.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerToolsConfig {

    @Bean
    public ToolCallbackProvider settlementToolCallbackProvider(
            ReconciliationMcpTools reconciliationMcpTools,
            RefundActionMcpTools refundActionMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(reconciliationMcpTools, refundActionMcpTools)
                .build();
    }
}
