-- A provider transaction id can settle only one refund. NULLs (not yet settled) stay allowed.

ALTER TABLE refund_approval_order
    ADD CONSTRAINT uk_approval_transaction_id UNIQUE (transaction_id);
