import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints an HS256 finance-reviewer token for local testing. Needs only a JDK (11+), no build:
 *
 * <pre>
 *   java tools/MintReviewerToken.java finance-lead@company.com            # 30-minute token
 *   java tools/MintReviewerToken.java controller@company.com 10           # 10-minute token
 * </pre>
 *
 * Reads MCP_REVIEWER_JWT_SECRET and MCP_REVIEWER_JWT_ISSUER from the environment and falls back to the published
 * demo values. Production deployments should get reviewer tokens from their identity provider instead.
 */
public class MintReviewerToken {

    private static final String DEMO_SECRET = "mcp-demo-reviewer-jwt-secret-2026-replace-me";
    private static final String DEFAULT_ISSUER = "spring-mcp-settlement-guard";

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("usage: java tools/MintReviewerToken.java <reviewer-email> [ttl-minutes]");
            System.exit(2);
        }
        String subject = args[0].trim();
        long ttlMinutes = args.length > 1 ? Long.parseLong(args[1]) : 30;
        String secret = envOr("MCP_REVIEWER_JWT_SECRET", DEMO_SECRET);
        String issuer = envOr("MCP_REVIEWER_JWT_ISSUER", DEFAULT_ISSUER);

        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + json(issuer) + "\",\"sub\":\"" + json(subject)
                + "\",\"roles\":[\"FINANCE_APPROVER\"],\"iat\":" + now + ",\"exp\":" + (now + ttlMinutes * 60) + "}";
        String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        System.out.println(signingInput + "." + b64(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8))));
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
