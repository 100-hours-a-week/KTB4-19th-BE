ALTER TABLE Messages
    CHANGE COLUMN trace_id turn_id VARCHAR(36) NULL COMMENT 'AI 한 턴의 입주민 메시지와 AI 응답 식별자';
