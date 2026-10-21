CREATE TABLE Chatbot_reply_outbox (
    event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    trace_id VARCHAR(36) NOT NULL,
    lock_token VARCHAR(36) NOT NULL,
    new_conversation BOOLEAN NOT NULL,
    previous_last_message_at DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    processing_started_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL
);

CREATE INDEX idx_chatbot_reply_outbox_processing ON Chatbot_reply_outbox (status, processing_started_at);
