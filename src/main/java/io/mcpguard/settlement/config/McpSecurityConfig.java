package io.mcpguard.settlement.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces strict separation of duties between external MCP agent callers
 * and human finance reviewers signing off on paused settlement actions.
 */
@Configuration
public class McpSecurityConfig {

    public static final String HEADER_AGENT_API_KEY = "X-MCP-API-Key";
    public static final String HEADER_REVIEWER_KEY = "X-Reviewer-Key";
    public static final String HEADER_REVIEWER_ID = "X-Reviewer-Id";

    public static final String ROLE_MCP_AGENT = "ROLE_MCP_AGENT";
    public static final String ROLE_FINANCE_APPROVER = "ROLE_FINANCE_APPROVER";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, GuardrailProperties properties) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, authEx) -> writeJsonError(
                                res, HttpStatus.UNAUTHORIZED, "Missing or invalid API credentials"))
                        .accessDeniedHandler((req, res, deniedEx) -> writeJsonError(
                                res, HttpStatus.FORBIDDEN, "Insufficient privileges for requested settlement boundary"))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/approvals/**").hasAuthority(ROLE_FINANCE_APPROVER)
                        .requestMatchers("/sse/**", "/mcp/**", "/api/demo/**").hasAuthority(ROLE_MCP_AGENT)
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new ApiKeyRoleFilter(properties), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeJsonError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + status.getReasonPhrase() + "\",\"message\":\"" + message + "\"}");
    }

    static final class ApiKeyRoleFilter extends OncePerRequestFilter {

        private final GuardrailProperties properties;

        ApiKeyRoleFilter(GuardrailProperties properties) {
            this.properties = properties;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {

            String reviewerKey = request.getHeader(HEADER_REVIEWER_KEY);
            String reviewerId = request.getHeader(HEADER_REVIEWER_ID);
            String agentKey = request.getHeader(HEADER_AGENT_API_KEY);

            // Roles are derived only from which server-side secret matched; callers can never assert a role.
            // Reviewer identity comes from a header here because the demo uses one shared reviewer key.
            // In production, replace both keys with OAuth2/JWT and read the reviewer from the token subject.
            if (StringUtils.hasText(reviewerKey)
                    && constantTimeEquals(reviewerKey, properties.security().reviewerApiKey())
                    && StringUtils.hasText(reviewerId)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        reviewerId.trim(),
                        null,
                        List.of(new SimpleGrantedAuthority(ROLE_FINANCE_APPROVER)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else if (StringUtils.hasText(agentKey)
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
