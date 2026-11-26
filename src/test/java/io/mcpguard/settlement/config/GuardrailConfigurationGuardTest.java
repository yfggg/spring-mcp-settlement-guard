package io.mcpguard.settlement.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class GuardrailConfigurationGuardTest {

    private static final String STRONG_SECRET = "reviewer-jwt-secret-with-at-least-32-bytes";

    private static GuardrailProperties props(String agent, String reviewerSecret, String hmac, boolean seed, boolean http) {
        return new GuardrailProperties(
                new GuardrailProperties.Mandate("USD", new BigDecimal("50.00"), new BigDecimal("200.00"), 24),
                new GuardrailProperties.Security(agent, reviewerSecret, "spring-mcp-settlement-guard", hmac),
                new GuardrailProperties.Recovery(15, 60000, 30),
                new GuardrailProperties.Demo(seed, http),
                new GuardrailProperties.Provider(true, 24, 7, 3_600_000));
    }

    private static void runGuard(GuardrailProperties properties) throws Exception {
        new GuardrailConfigurationGuard().publishedDemoSecretGuard(properties).run(new DefaultApplicationArguments());
    }

    @Test
    void refusesBlankSecrets() {
        assertThatThrownBy(() -> runGuard(props(" ", STRONG_SECRET, "hmac-key", false, false)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> runGuard(props("agent-key", "", "hmac-key", true, true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesReviewerSecretsShorterThan32Bytes() {
        assertThatThrownBy(() -> runGuard(props("agent-key", "too-short", "hmac-key", true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void refusesPublishedSecretsOnceDemoModeIsOff() {
        assertThatThrownBy(() -> runGuard(props(
                GuardrailConfigurationGuard.PUBLISHED_AGENT_KEY, STRONG_SECRET, "hmac-key", false, false)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> runGuard(props(
                "agent-key", GuardrailConfigurationGuard.PUBLISHED_REVIEWER_JWT_SECRET, "hmac-key", false, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsPublishedSecretsInDemoModeAndCustomSecretsAnywhere() {
        assertThatCode(() -> runGuard(props(
                GuardrailConfigurationGuard.PUBLISHED_AGENT_KEY,
                GuardrailConfigurationGuard.PUBLISHED_REVIEWER_JWT_SECRET,
                GuardrailConfigurationGuard.PUBLISHED_SNAPSHOT_KEY, true, true))).doesNotThrowAnyException();
        assertThatCode(() -> runGuard(props("agent-key", STRONG_SECRET, "hmac-key", false, false)))
                .doesNotThrowAnyException();
    }
}
