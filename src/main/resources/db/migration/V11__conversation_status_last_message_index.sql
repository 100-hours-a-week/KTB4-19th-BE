CREATE INDEX idx_conversations_status_last_message ON Conversations (conversation_status, last_message_at);
