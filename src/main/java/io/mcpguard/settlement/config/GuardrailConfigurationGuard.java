package io.mcpguard.settlement.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * The published quick-start keys are fine while the demo seed and demo HTTP endpoint are on.
 * A deployment that turns those off must bring its own credentials.
 */
@Configuration
public class GuardrailConfigurationGuard {

    static final String PUBLISHED_AGENT_KEY = "mcp-demo-agent-key-2026";
    static final String PUBLISHED_REVIEWER_KEY = "mcp-demo-reviewer-key-2026";
    static final String PUBLISHED_SNAPSHOT_KEY = "mcp-demo-snapshot-hmac-2026";

    private static final Logger log = LoggerFactory.getLogger(GuardrailConfigurationGuard.class);

    @Bean
    ApplicationRunner publishedDemoSecretGuard(GuardrailProperties properties) {
        return args -> {
            GuardrailProperties.Security security = properties.security();
            if (!StringUtils.hasText(security.agentApiKey())
                    || !StringUtils.hasText(security.reviewerApiKey())
                    || !StringUtils.hasText(security.snapshotHmacKey())) {
                throw new IllegalStateException(
                        "Agent, reviewer, and snapshot HMAC keys must be non-blank. "
                                + "Set MCP_AGENT_API_KEY, MCP_REVIEWER_API_KEY, and MCP_SNAPSHOT_HMAC_KEY.");
            }
            boolean demoMode = properties.demo().httpEnabled() || properties.demo().seedEnabled();
            boolean publishedSecrets = usesPublishedSecret(properties);
            if (publishedSecrets && !demoMode) {
                throw new IllegalStateException(
                        "Refusing to start with the published demo credentials while demo seed and the demo HTTP endpoint are both disabled. "
                                + "Set MCP_AGENT_API_KEY, MCP_REVIEWER_API_KEY, and MCP_SNAPSHOT_HMAC_KEY.");
            }
            if (publishedSecrets) {
                log.warn("Published demo credentials are active. Replace MCP_AGENT_API_KEY, MCP_REVIEWER_API_KEY, "
                        + "and MCP_SNAPSHOT_HMAC_KEY before exposing this process beyond a local demo.");
            }
        };
    }

    private static boolean usesPublishedSecret(GuardrailProperties properties) {
        GuardrailProperties.Security security = properties.security();
        return PUBLISHED_AGENT_KEY.equals(security.agentApiKey())
                || PUBLISHED_REVIEWER_KEY.equals(security.reviewerApiKey())
                || PUBLISHED_SNAPSHOT_KEY.equals(security.snapshotHmacKey());
    }
}
