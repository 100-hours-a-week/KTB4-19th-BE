ALTER TABLE Conversations
    ADD COLUMN current_route VARCHAR(20) NULL COMMENT 'AI 대화 경로: COMPLAINT, KNOWLEDGE, CLARIFY',
    ADD COLUMN ai_conversation_state VARCHAR(20) NULL COMMENT 'AI 대화 단계: COLLECTING, ACTION_SELECTION, GUIDING, READY_TO_CONFIRM, CLARIFYING',
    ADD COLUMN draft_location VARCHAR(50) NULL COMMENT '수집 중인 발생 위치',
    ADD COLUMN draft_symptom VARCHAR(100) NULL COMMENT '수집 중인 증상',
    ADD COLUMN draft_occurred_at DATETIME(6) NULL COMMENT '수집 중인 발생 시점';

ALTER TABLE Messages
    MODIFY COLUMN content VARCHAR(800) NOT NULL COMMENT '메시지 본문';

ALTER TABLE Complaint_Details
    MODIFY COLUMN occurred_time DATETIME(6) NULL COMMENT '불편이 발생한 시각';
