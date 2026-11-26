package io.mcpguard.settlement.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class GuardrailConfigurationGuardTest {

    private static GuardrailProperties props(String agent, String reviewer, String hmac, boolean seed, boolean http) {
        return new GuardrailProperties(
                new GuardrailProperties.Mandate("USD", new BigDecimal("50.00"), new BigDecimal("200.00"), 24),
                new GuardrailProperties.Security(agent, reviewer, hmac),
                new GuardrailProperties.Recovery(15, 60000),
                new GuardrailProperties.Demo(seed, http));
    }

    private static void runGuard(GuardrailProperties properties) throws Exception {
        new GuardrailConfigurationGuard().publishedDemoSecretGuard(properties).run(new DefaultApplicationArguments());
    }

    @Test
    void refusesBlankKeys() {
        assertThatThrownBy(() -> runGuard(props(" ", "reviewer-key", "hmac-key", false, false)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> runGuard(props("agent-key", "", "hmac-key", true, true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesPublishedKeysOnceDemoModeIsOff() {
        assertThatThrownBy(() -> runGuard(props(
                GuardrailConfigurationGuard.PUBLISHED_AGENT_KEY, "reviewer-key", "hmac-key", false, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsPublishedKeysInDemoModeAndCustomKeysAnywhere() {
        assertThatCode(() -> runGuard(props(
                GuardrailConfigurationGuard.PUBLISHED_AGENT_KEY,
                GuardrailConfigurationGuard.PUBLISHED_REVIEWER_KEY,
                GuardrailConfigurationGuard.PUBLISHED_SNAPSHOT_KEY, true, true))).doesNotThrowAnyException();
        assertThatCode(() -> runGuard(props("agent-key", "reviewer-key", "hmac-key", false, false)))
                .doesNotThrowAnyException();
    }
}
