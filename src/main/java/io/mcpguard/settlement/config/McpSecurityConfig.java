package io.mcpguard.settlement.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Separation of duties between machine callers and people.
 *
 * <ul>
 *   <li>MCP agents authenticate with {@code X-MCP-API-Key} and receive only {@code ROLE_MCP_AGENT}.</li>
 *   <li>Finance reviewers present a signed JWT ({@code Authorization: Bearer ...}). The reviewer identity is the
 *       token subject, and {@code ROLE_FINANCE_APPROVER} comes only from the token's {@code roles} claim.</li>
 * </ul>
 *
 * The demo signs reviewer tokens with a shared HS256 secret (see {@code tools/MintReviewerToken.java}). To use an
 * identity provider instead, replace {@link #reviewerJwtDecoder} with {@code JwtDecoders.fromIssuerLocation(...)}.
 */
@Configuration
public class McpSecurityConfig {

    public static final String HEADER_AGENT_API_KEY = "X-MCP-API-Key";

    public static final String ROLE_MCP_AGENT = "ROLE_MCP_AGENT";
    public static final String ROLE_FINANCE_APPROVER = "ROLE_FINANCE_APPROVER";
    public static final String REVIEWER_ROLE_CLAIM = "roles";
    public static final String REVIEWER_ROLE_VALUE = "FINANCE_APPROVER";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, GuardrailProperties properties, JwtDecoder reviewerJwtDecoder) throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, authEx) -> writeJsonError(
                res, HttpStatus.UNAUTHORIZED, "Missing or invalid credentials");
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((req, res, deniedEx) -> writeJsonError(
                                res, HttpStatus.FORBIDDEN, "Insufficient privileges for requested settlement boundary"))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/approvals/**").hasAuthority(ROLE_FINANCE_APPROVER)
                        .requestMatchers("/sse/**", "/mcp/**", "/api/demo/**").hasAuthority(ROLE_MCP_AGENT)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(unauthorized)
                        .jwt(jwt -> jwt
                                .decoder(reviewerJwtDecoder)
                                .jwtAuthenticationConverter(reviewerAuthenticationConverter())))
                .addFilterBefore(new AgentApiKeyFilter(properties), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /** HS256 verifier with issuer, expiry, and subject checks. Tokens without {@code exp} or {@code sub} are refused. */
    @Bean
    public JwtDecoder reviewerJwtDecoder(GuardrailProperties properties) {
        GuardrailProperties.Security security = properties.security();
        SecretKeySpec key = new SecretKeySpec(
                security.reviewerJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(security.reviewerJwtIssuer()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText)));
        return decoder;
    }

    private static Converter<Jwt, AbstractAuthenticationToken> reviewerAuthenticationConverter() {
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList(REVIEWER_ROLE_CLAIM);
            List<GrantedAuthority> authorities = roles != null && roles.contains(REVIEWER_ROLE_VALUE)
                    ? List.of(new SimpleGrantedAuthority(ROLE_FINANCE_APPROVER))
                    : List.of();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    private static void writeJsonError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * Grants {@code ROLE_MCP_AGENT} when the agent key matches. Requests that carry a bearer token are left to the
     * JWT filter, so a caller cannot combine an agent key with a reviewer token.
     */
    static final class AgentApiKeyFilter extends OncePerRequestFilter {

        private final GuardrailProperties properties;

        AgentApiKeyFilter(GuardrailProperties properties) {
            this.properties = properties;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            String agentKey = request.getHeader(HEADER_AGENT_API_KEY);
            boolean hasBearer = StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (!hasBearer
                    && StringUtils.hasText(agentKey)
                    && constantTimeEquals(agentKey, properties.security().agentApiKey())) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "mcp-agent-client",
                        null,
                        List.of(new SimpleGrantedAuthority(ROLE_MCP_AGENT)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            filterChain.doFilter(request, response);
        }

        private static boolean constantTimeEquals(String provided, String expected) {
            if (provided == null || expected == null) {
                return false;
            }
            return MessageDigest.isEqual(
                    provided.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));
        }
    }
}
