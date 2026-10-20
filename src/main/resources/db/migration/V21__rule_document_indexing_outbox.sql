CREATE TABLE Rule_document_indexing_outbox (
    outbox_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    building_id BIGINT NOT NULL,
    document_id BIGINT NULL,
    document_version INT NULL,
    operation VARCHAR(10) NOT NULL,
    trace_id VARCHAR(64) NULL,
    status VARCHAR(10) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    INDEX idx_rule_document_indexing_outbox_due (status, next_retry_at, outbox_id)
);
