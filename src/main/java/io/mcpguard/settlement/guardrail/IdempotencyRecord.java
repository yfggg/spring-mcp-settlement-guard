package io.mcpguard.settlement.guardrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotency_key"),
        indexes = @Index(name = "idx_idempotency_state_updated", columnList = "state,updated_at")
)
public class IdempotencyRecord {

    public enum State {
        IN_PROGRESS,
        COMPLETED,
        UNKNOWN_SUSPENDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", nullable = false, length = 32)
    private State state;

    @Column(name = "response_json", length = 2048)
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String payloadHash, State state, Instant now) {
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.state = state;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public State getState() {
        return state;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void markCompleted(String responseJson, Instant now) {
        this.state = State.COMPLETED;
        this.responseJson = responseJson;
        this.updatedAt = now;
    }

    public void markUnknownSuspended(String diagnosticJson, Instant now) {
        this.state = State.UNKNOWN_SUSPENDED;
        this.responseJson = diagnosticJson;
        this.updatedAt = now;
    }
}
