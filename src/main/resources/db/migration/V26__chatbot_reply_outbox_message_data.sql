ALTER TABLE Chatbot_reply_outbox
    ADD COLUMN message_data JSON NULL AFTER previous_last_message_at;
