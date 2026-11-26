package io.mcpguard.settlement.guardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stripe-style 3-state relational idempotency store backed by a database unique constraint.
 * Claims keys in an isolated REQUIRES_NEW short transaction before side effects execute.
 * UNKNOWN_SUSPENDED is reachable only from IN_PROGRESS, so a finished receipt cannot be erased.
 */
@Service
public class IdempotencyKeyStore {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;

    public IdempotencyKeyStore(
            IdempotencyRecordRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ClaimResult tryClaimOrReplay(String idempotencyKey, String payloadHash) {
        try {
            return requiresNewTx.execute(status -> {
                Optional<IdempotencyRecord> existing = repository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    return evaluateExisting(existing.get(), payloadHash);
                }
                repository.saveAndFlush(new IdempotencyRecord(
                        idempotencyKey,
                        payloadHash,
                        IdempotencyRecord.State.IN_PROGRESS,
                        Instant.now()
                ));
                return ClaimResult.claimed();
            });
        } catch (DataIntegrityViolationException raceEx) {
            return requiresNewTx.execute(status -> {
                IdempotencyRecord winner = repository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> raceEx);
                return evaluateExisting(winner, payloadHash);
            });
        }
    }

    /**
     * @return false when the key is no longer IN_PROGRESS. Callers that already know the
     * business outcome should then {@link #recordTerminalDecision}.
     */
    public boolean markCompleted(String idempotencyKey, Object responsePayload) {
        String serialized = toJson(responsePayload);
        Integer updated = requiresNewTx.execute(status -> repository.completeIfInProgress(
                idempotencyKey, serialized, Instant.now()));
        return updated != null && updated == 1;
    }

    /**
     * Overwrites the cached body with a known terminal decision (executed, rejected, expired, released).
     * Used after human review so a replay no longer returns the original PENDING_APPROVAL payload.
     */
    public void recordTerminalDecision(String idempotencyKey, Object responsePayload) {
        String serialized = toJson(responsePayload);
        requiresNewTx.executeWithoutResult(status -> {
            IdempotencyRecord record = repository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (record == null) {
                return;
            }
            record.markCompleted(serialized, Instant.now());
            repository.saveAndFlush(record);
        });
    }

    /**
     * Freezes an in-flight key. A COMPLETED receipt is left untouched.
     *
     * @return false when there was nothing in progress to freeze
     */
    public boolean markUnknownSuspended(String idempotencyKey, String diagnosticMessage) {
        Integer updated = requiresNewTx.execute(status -> repository.suspendIfInProgress(
                idempotencyKey, diagnosticMessage, Instant.now()));
        return updated != null && updated == 1;
    }

    public void releaseInProgress(String idempotencyKey) {
        requiresNewTx.executeWithoutResult(status -> repository.deleteInProgress(idempotencyKey));
    }

    /**
     * Drops a suspended key. Callers must first confirm that no approval row exists under this key,
     * which means nothing was reserved and the gateway was never called.
     */
    public boolean releaseSuspended(String idempotencyKey) {
        Integer deleted = requiresNewTx.execute(status -> repository.deleteSuspended(idempotencyKey));
        return deleted != null && deleted == 1;
    }

    /** Freezes keys whose owning request never finished (for example after a process crash). */
    public int suspendStaleInProgress(Instant cutoff, Instant now) {
        Integer updated = requiresNewTx.execute(status -> repository.suspendStaleInProgress(
                cutoff,
                now,
                "{\"status\":\"UNKNOWN_SUSPENDED\",\"reason\":\"Request never completed; manual reconciliation required\"}"));
        return updated == null ? 0 : updated;
    }

    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached idempotent response", e);
        }
    }

    private ClaimResult evaluateExisting(IdempotencyRecord existing, String incomingPayloadHash) {
        if (!existing.getPayloadHash().equals(incomingPayloadHash)) {
            throw new GuardrailExceptions.IdempotencyConflictException(
                    "Idempotency key '" + existing.getIdempotencyKey()
                            + "' was already used with a different request payload");
        }
        if (existing.getState() == IdempotencyRecord.State.COMPLETED) {
            return ClaimResult.replayed(existing.getResponseJson());
        }
        if (existing.getState() == IdempotencyRecord.State.IN_PROGRESS) {
            return ClaimResult.inProgress();
        }
        return ClaimResult.suspended(existing.getResponseJson());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotent response", e);
        }
    }

    public enum ClaimOutcome {
        CLAIMED,
        REPLAY,
        IN_PROGRESS,
        SUSPENDED
    }

    public record ClaimResult(ClaimOutcome outcome, String cachedResponseJson) {
        public static ClaimResult claimed() {
            return new ClaimResult(ClaimOutcome.CLAIMED, null);
        }

        public static ClaimResult replayed(String json) {
            return new ClaimResult(ClaimOutcome.REPLAY, json);
        }

        public static ClaimResult inProgress() {
            return new ClaimResult(ClaimOutcome.IN_PROGRESS, null);
        }

        public static ClaimResult suspended(String json) {
            return new ClaimResult(ClaimOutcome.SUSPENDED, json);
        }
    }
}
