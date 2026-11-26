package io.mcpguard.settlement.guardrail;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GuardrailExceptionHandler {

    @ExceptionHandler(GuardrailExceptions.InvalidSettlementRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(
            GuardrailExceptions.InvalidSettlementRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleBeanValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", "Invalid request payload"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleMethodValidation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", ex.getMessage()));
    }

    @ExceptionHandler(GuardrailExceptions.ApprovalNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(GuardrailExceptions.ApprovalNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", ex.getMessage()));
    }

    @ExceptionHandler(GuardrailExceptions.IdempotencyInProgressException.class)
    public ResponseEntity<Map<String, String>> handleInProgress(GuardrailExceptions.IdempotencyInProgressException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "IDEMPOTENCY_IN_PROGRESS", "message", ex.getMessage()));
    }

    @ExceptionHandler({
            GuardrailExceptions.IdempotencyConflictException.class,
            GuardrailExceptions.ApprovalStateConflictException.class
    })
    public ResponseEntity<Map<String, String>> handleConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONFLICT", "message", ex.getMessage()));
    }

    @ExceptionHandler(GuardrailExceptions.TamperedSnapshotException.class)
    public ResponseEntity<Map<String, String>> handleTampered(GuardrailExceptions.TamperedSnapshotException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "SNAPSHOT_INTEGRITY_VIOLATION", "message", ex.getMessage()));
    }

    @ExceptionHandler(GuardrailExceptions.PayoutOutcomeUnknownException.class)
    public ResponseEntity<Map<String, String>> handlePayoutUnknown(GuardrailExceptions.PayoutOutcomeUnknownException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "PAYOUT_OUTCOME_UNKNOWN", "message", ex.getMessage()));
    }
}
