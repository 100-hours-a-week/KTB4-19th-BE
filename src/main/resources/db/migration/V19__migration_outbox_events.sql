CREATE TABLE Migration_outbox_events (
    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    direction VARCHAR(20) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    operation VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL,
    next_retry_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL
);

CREATE INDEX idx_migration_outbox_events_unprocessed ON Migration_outbox_events (direction, processed_at, event_id);

CREATE TABLE Migration_applied_events (
    direction VARCHAR(20) NOT NULL,
    event_id BIGINT NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (direction, event_id)
);
