package io.mcpguard.settlement;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Signs finance-reviewer tokens with the test profile's secret, mirroring {@code tools/MintReviewerToken.java}. */
public final class ReviewerTokens {

    public static final String TEST_SECRET = "test-reviewer-jwt-secret-0123456789abcdef";
    public static final String ISSUER = "spring-mcp-settlement-guard";
    public static final List<String> FINANCE_ROLE = List.of("FINANCE_APPROVER");

    private ReviewerTokens() {
    }

    /** {@code Authorization} header value for a valid 10-minute finance reviewer token. */
    public static String bearer(String reviewer) {
        return "Bearer " + mint(TEST_SECRET, ISSUER, reviewer, FINANCE_ROLE, Duration.ofMinutes(10));
    }

    /** Any argument may be null to omit that claim; a negative ttl yields an expired token. */
    public static String mint(String secret, String issuer, String subject, List<String> roles, Duration ttl) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issueTime(new Date());
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (subject != null) {
            claims.subject(subject);
        }
        if (roles != null) {
            claims.claim("roles", roles);
        }
        if (ttl != null) {
            claims.expirationTime(Date.from(Instant.now().plus(ttl)));
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign test token", e);
        }
    }
}
