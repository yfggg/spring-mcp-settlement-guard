package io.mcpguard.settlement.domain;

import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ensures a lockable anchor row exists for an order in an isolated short transaction
 * so concurrent callers never poison their main transaction on first-time order creation.
 */
@Service
public class OrderAnchorLockService {

    private final OrderSettlementAnchorRepository anchorRepository;
    private final TransactionTemplate requiresNewTx;

    public OrderAnchorLockService(
            OrderSettlementAnchorRepository anchorRepository,
            PlatformTransactionManager transactionManager) {
        this.anchorRepository = anchorRepository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void ensureAnchorExists(String orderId) {
        if (anchorRepository.findByOrderId(orderId).isPresent()) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> {
                if (anchorRepository.findByOrderId(orderId).isEmpty()) {
                    anchorRepository.saveAndFlush(new OrderSettlementAnchor(orderId, Instant.now()));
                }
            });
        } catch (DataIntegrityViolationException ignored) {
            // Another concurrent thread won the insert race; safe to proceed to FOR UPDATE lock.
        }
    }
}
