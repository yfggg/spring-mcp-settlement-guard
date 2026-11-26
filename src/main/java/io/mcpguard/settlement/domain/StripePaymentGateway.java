package io.mcpguard.settlement.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mcpguard.settlement.config.GuardrailProperties;
import io.mcpguard.settlement.config.StripeProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Live Stripe REST API adapter activated under the {@code stripe} Spring profile.
 *
 * <p>Forwards the guardrail's idempotency reference via Stripe's {@code Idempotency-Key} header and
 * stores {@code metadata[idempotency_ref]} on every refund so {@link #lookupRefund} and
 * {@code PayoutReconciliationJob} can reconcile against Stripe without extra schema tables.
 */
@Component
@Primary
@Profile("stripe")
public class StripePaymentGateway implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);

    private final StripeProperties stripeProperties;
    private final GuardrailProperties guardrailProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiBaseUrl;
    private final ConcurrentHashMap<String, String> paymentIntentByOrderId = new ConcurrentHashMap<>();

    public StripePaymentGateway(
            StripeProperties stripeProperties,
            GuardrailProperties guardrailProperties,
            ObjectMapper objectMapper) {
        if (!StringUtils.hasText(stripeProperties.secretKey())) {
            throw new IllegalStateException(
                    "guardrail.stripe.secret-key (STRIPE_SECRET_KEY) is required when the 'stripe' profile is active.");
        }
        this.stripeProperties = stripeProperties;
        this.guardrailProperties = guardrailProperties;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = trimTrailingSlash(stripeProperties.apiBaseUrl());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public PaymentExecutionResult executeRefund(
            String orderId,
            BigDecimal amount,
            String currency,
            String idempotencyRef) {
        String paymentIntentId = resolvePaymentIntentId(orderId);
        if (paymentIntentId == null) {
            throw new StripeGatewayException("No captured Stripe PaymentIntent found for order " + orderId);
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("payment_intent", paymentIntentId);
        form.put("amount", Long.toString(toMinorUnits(amount)));
        form.put("metadata[order_id]", orderId);
        form.put("metadata[idempotency_ref]", idempotencyRef);

        String stripeIdempotencyKey = stripeIdempotencyKey(orderId, idempotencyRef);
        JsonNode response = postForm("/v1/refunds", form, stripeIdempotencyKey);
        String status = response.path("status").asText("");
        PaymentExecutionResult result = toExecutionResult(orderId, response);

        if ("succeeded".equalsIgnoreCase(status)) {
            log.info("Settled Stripe refund {} ({} {}) for order {} [pi={}]",
                    result.transactionId(), result.amount(), result.currency(), orderId, paymentIntentId);
            return result;
        }
        throw new StripeGatewayException(
                "Stripe refund " + result.transactionId() + " returned non-settled status: " + status);
    }

    @Override
    public ProviderRefundLookup lookupRefund(String orderId, String idempotencyRef) {
        String paymentIntentId = resolvePaymentIntentId(orderId);
        if (paymentIntentId == null) {
            return ProviderRefundLookup.notFound();
        }

        String path = "/v1/refunds?limit=100&payment_intent=" + urlEncode(paymentIntentId);
        JsonNode list = getJson(path);
        for (JsonNode refundNode : list.path("data")) {
            String recordedRef = refundNode.path("metadata").path("idempotency_ref").asText("");
            if (!idempotencyRef.equals(recordedRef)) {
                continue;
            }
            PaymentExecutionResult result = toExecutionResult(orderId, refundNode);
            String status = refundNode.path("status").asText("").toLowerCase(Locale.ROOT);
            return switch (status) {
                case "succeeded" -> ProviderRefundLookup.of(ProviderRefundLookup.State.SUCCEEDED, true, result);
                case "pending", "requires_action" ->
                        ProviderRefundLookup.of(ProviderRefundLookup.State.PENDING, false, result);
                case "failed" -> ProviderRefundLookup.of(ProviderRefundLookup.State.FAILED, true, result);
                case "canceled" -> ProviderRefundLookup.of(ProviderRefundLookup.State.CANCELLED, true, result);
                default -> throw new StripeGatewayException("Unrecognized Stripe refund status: " + status);
            };
        }
        return ProviderRefundLookup.notFound();
    }

    @Override
    public void cancelRefund(String orderId, String idempotencyRef) {
        if (!cancelSupported()) {
            throw new CancelNotSupportedException("Stripe card refunds settle immediately and cannot be cancelled");
        }
        ProviderRefundLookup existing = lookupRefund(orderId, idempotencyRef);
        if (existing.state() == ProviderRefundLookup.State.NOT_FOUND
                || existing.state() == ProviderRefundLookup.State.CANCELLED
                || existing.state() == ProviderRefundLookup.State.SUCCEEDED) {
            return;
        }
        String refundId = existing.refund().transactionId();
        postForm("/v1/refunds/" + urlEncode(refundId) + "/cancel", Map.of(), null);
    }

    @Override
    public boolean cancelSupported() {
        return guardrailProperties.provider().cancelSupported();
    }

    /**
     * Ensures an unrefunded test PaymentIntent exists in Stripe for a demo invoice order.
     * Refuses to run against non-test keys ({@code sk_live_...}).
     */
    public String ensureDemoCapturedPaymentIntent(
            String orderId,
            BigDecimal capturedAmount,
            String currency,
            String customerEmail) {
        if (!stripeProperties.secretKey().trim().startsWith("sk_test_")) {
            throw new IllegalStateException("Refusing to seed synthetic Stripe PaymentIntents with a non-test key.");
        }
        long expectedMinor = toMinorUnits(capturedAmount);
        String expectedCurrency = currency.toLowerCase(Locale.ROOT);

        JsonNode recent = getJson("/v1/payment_intents?limit=25&expand[]=data.latest_charge");
        for (JsonNode pi : recent.path("data")) {
            String metaOrder = pi.path("metadata").path("order_id").asText("");
            String status = pi.path("status").asText("");
            long amount = pi.path("amount_received").asLong(0L);
            String piCurrency = pi.path("currency").asText("");
            long refunded = pi.path("latest_charge").path("amount_refunded").asLong(0L);
            if (orderId.equals(metaOrder)
                    && "succeeded".equals(status)
                    && amount == expectedMinor
                    && expectedCurrency.equalsIgnoreCase(piCurrency)
                    && refunded == 0L) {
                String existingId = pi.path("id").asText();
                paymentIntentByOrderId.put(orderId, existingId);
                log.info("Reusing unrefunded Stripe test PaymentIntent {} ({} {}) for order {}",
                        existingId, capturedAmount, currency, orderId);
                return existingId;
            }
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", Long.toString(expectedMinor));
        form.put("currency", expectedCurrency);
        form.put("payment_method", "pm_card_visa");
        form.put("confirm", "true");
        form.put("automatic_payment_methods[enabled]", "true");
        form.put("automatic_payment_methods[allow_redirects]", "never");
        form.put("description", "MCP settlement demo capture for " + orderId + " (" + customerEmail + ")");
        form.put("metadata[order_id]", orderId);

        JsonNode created = postForm("/v1/payment_intents", form, null);
        String piId = created.path("id").asText("");
        if (!StringUtils.hasText(piId) || !"succeeded".equals(created.path("status").asText(""))) {
            throw new StripeGatewayException("Failed to create confirmed test PaymentIntent for " + orderId);
        }
        paymentIntentByOrderId.put(orderId, piId);
        log.info("Created Stripe test PaymentIntent {} ({} {}) for order {}",
                piId, capturedAmount, currency, orderId);
        return piId;
    }

    public void registerOrderPaymentIntent(String orderId, String paymentIntentId) {
        paymentIntentByOrderId.put(orderId, paymentIntentId);
    }

    private String resolvePaymentIntentId(String orderId) {
        String cached = paymentIntentByOrderId.get(orderId);
        if (cached != null) {
            return cached;
        }
        JsonNode list = getJson("/v1/payment_intents?limit=50");
        for (JsonNode pi : list.path("data")) {
            if (orderId.equals(pi.path("metadata").path("order_id").asText(""))
                    && "succeeded".equals(pi.path("status").asText(""))) {
                String foundId = pi.path("id").asText();
                paymentIntentByOrderId.put(orderId, foundId);
                return foundId;
            }
        }
        return null;
    }

    private JsonNode getJson(String pathAndQuery) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + stripeProperties.secretKey().trim())
                .GET()
                .build();
        return sendAndParse(request);
    }

    private JsonNode postForm(String path, Map<String, String> formFields, String idempotencyKey) {
        String encodedBody = formFields.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + stripeProperties.secretKey().trim())
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (StringUtils.hasText(idempotencyKey)) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(encodedBody, StandardCharsets.UTF_8))
                .build();
        return sendAndParse(request);
    }

    private JsonNode sendAndParse(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new StripeGatewayException("Interrupted while calling Stripe API", ie);
        } catch (IOException ioe) {
            throw new StripeGatewayException("Network failure while calling Stripe API: " + ioe.getMessage(), ioe);
        }

        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(response.body());
        } catch (IOException parseEx) {
            throw new StripeGatewayException(
                    "Invalid JSON from Stripe API (HTTP " + response.statusCode() + ")", parseEx);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = parsed.path("error").path("message").asText("HTTP " + response.statusCode());
            String code = parsed.path("error").path("code").asText("");
            throw new StripeGatewayException(
                    "Stripe API error (" + response.statusCode()
                            + (code.isEmpty() ? "" : "/" + code) + "): " + message);
        }
        return parsed;
    }

    private static PaymentExecutionResult toExecutionResult(String orderId, JsonNode refundNode) {
        String id = refundNode.path("id").asText();
        long amountMinor = refundNode.path("amount").asLong();
        String currency = refundNode.path("currency").asText("usd").toUpperCase(Locale.ROOT);
        long createdEpoch = refundNode.path("created").asLong(Instant.now().getEpochSecond());
        return new PaymentExecutionResult(
                id,
                orderId,
                fromMinorUnits(amountMinor),
                currency,
                Instant.ofEpochSecond(createdEpoch)
        );
    }

    static long toMinorUnits(BigDecimal amount) {
        return DeterministicReconciler.scale(amount).movePointRight(2).longValueExact();
    }

    static BigDecimal fromMinorUnits(long minorUnits) {
        return DeterministicReconciler.scale(BigDecimal.valueOf(minorUnits, 2));
    }

    private static String stripeIdempotencyKey(String orderId, String idempotencyRef) {
        String combined = "mcpguard:" + orderId + ":" + idempotencyRef;
        return combined.length() <= 255 ? combined : combined.substring(0, 255);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static class StripeGatewayException extends RuntimeException {
        public StripeGatewayException(String message) {
            super(message);
        }

        public StripeGatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
