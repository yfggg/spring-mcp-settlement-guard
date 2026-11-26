package io.mcpguard.settlement;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Surefire and Failsafe share one PostgreSQL database in CI. Each Spring context truncates
 * leftover rows before the demo seed runs. H2 stays per-JVM and is left alone.
 * Flyway still applies V1 and V2 on the empty database before this runner starts.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PostgresTestSchemaReset implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final String datasourceUrl;

    PostgresTestSchemaReset(JdbcTemplate jdbcTemplate, org.springframework.core.env.Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceUrl = environment.getProperty("spring.datasource.url", "");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!datasourceUrl.startsWith("jdbc:postgresql:")) {
            return;
        }
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    payout_reconciliation_exception,
                    approval_event_outbox,
                    payout_resolution,
                    refund_approval_order,
                    idempotency_record,
                    tool_execution_audit_log,
                    settlement_invoice_item,
                    order_settlement_anchor
                RESTART IDENTITY CASCADE
                """);
    }
}
