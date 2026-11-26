package io.mcpguard.settlement.domain;

import io.mcpguard.settlement.config.GuardrailProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Binds a paused refund to the amount, currency, and reason the reviewer is signing.
 * The MAC key lives only in server configuration, so rewriting both the amount and the
 * stored digest still fails verification.
 */
@Component
public class SnapshotHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final String hmacKey;

    public SnapshotHasher(GuardrailProperties properties) {
        String configured = properties.security().snapshotHmacKey();
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("guardrail.security.snapshot-hmac-key is required");
        }
        this.hmacKey = configured;
    }

    public String hash(String orderId, BigDecimal scaledAmount, String currency, String reasonCode) {
        return hash(hmacKey, orderId, scaledAmount, currency, reasonCode);
    }

    public static String hash(
            String hmacKey,
            String orderId,
            BigDecimal scaledAmount,
            String currency,
            String reasonCode) {
        String canonical = orderId
                + "|" + scaledAmount.toPlainString()
                + "|" + currency.toUpperCase(Locale.ROOT)
                + "|" + reasonCode;
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
