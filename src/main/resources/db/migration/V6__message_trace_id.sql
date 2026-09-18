ALTER TABLE Messages
    ADD COLUMN trace_id VARCHAR(36) NULL COMMENT 'AI 요청 추적 ID, 입주민 메시지 하나와 AI 응답 메시지 하나가 같은 값으로 묶인다';
