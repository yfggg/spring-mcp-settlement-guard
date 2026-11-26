package io.mcpguard.settlement.config;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * The published quick-start secrets are fine while the demo seed and demo HTTP endpoint are on.
 * A deployment that turns those off must bring its own credentials.
 */
@Configuration
public class GuardrailConfigurationGuard {

    static final String PUBLISHED_AGENT_KEY = "mcp-demo-agent-key-2026";
    static final String PUBLISHED_REVIEWER_JWT_SECRET = "mcp-demo-reviewer-jwt-secret-2026-replace-me";
    static final String PUBLISHED_SNAPSHOT_KEY = "mcp-demo-snapshot-hmac-2026";
    static final int MIN_JWT_SECRET_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(GuardrailConfigurationGuard.class);

    @Bean
    ApplicationRunner publishedDemoSecretGuard(GuardrailProperties properties) {
        return args -> {
            GuardrailProperties.Security security = properties.security();
            if (!StringUtils.hasText(security.agentApiKey())
                    || !StringUtils.hasText(security.reviewerJwtSecret())
                    || !StringUtils.hasText(security.snapshotHmacKey())) {
                throw new IllegalStateException(
                        "Agent key, reviewer JWT secret, and snapshot HMAC key must be non-blank. "
                                + "Set MCP_AGENT_API_KEY, MCP_REVIEWER_JWT_SECRET, and MCP_SNAPSHOT_HMAC_KEY.");
            }
            if (security.reviewerJwtSecret().getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
                throw new IllegalStateException(
                        "MCP_REVIEWER_JWT_SECRET must be at least " + MIN_JWT_SECRET_BYTES + " bytes for HS256.");
            }
            boolean demoMode = properties.demo().httpEnabled() || properties.demo().seedEnabled();
            boolean publishedSecrets = usesPublishedSecret(security);
            if (publishedSecrets && !demoMode) {
                throw new IllegalStateException(
                        "Refusing to start with the published demo secrets while demo seed and the demo HTTP endpoint are both disabled. "
                                + "Set MCP_AGENT_API_KEY, MCP_REVIEWER_JWT_SECRET, and MCP_SNAPSHOT_HMAC_KEY.");
            }
            if (publishedSecrets) {
                log.warn("Published demo secrets are active. Replace MCP_AGENT_API_KEY, MCP_REVIEWER_JWT_SECRET, "
                        + "and MCP_SNAPSHOT_HMAC_KEY before exposing this process beyond a local demo.");
            }
        };
    }

    private static boolean usesPublishedSecret(GuardrailProperties.Security security) {
        return PUBLISHED_AGENT_KEY.equals(security.agentApiKey())
                || PUBLISHED_REVIEWER_JWT_SECRET.equals(security.reviewerJwtSecret())
                || PUBLISHED_SNAPSHOT_KEY.equals(security.snapshotHmacKey());
    }
}
