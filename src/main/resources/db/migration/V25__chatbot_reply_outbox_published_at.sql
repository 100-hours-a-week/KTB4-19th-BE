ALTER TABLE Chatbot_reply_outbox
    ADD COLUMN published_at DATETIME(6) NULL AFTER attempt_count;

CREATE INDEX idx_chatbot_reply_outbox_relay ON Chatbot_reply_outbox (status, published_at, updated_at);
