-- 건물·호실·초대코드·첨부·규칙문서·대화·메시지·민원·알림 스키마.
-- FK 순서 때문에 참조되는 테이블을 먼저 만든다.

CREATE TABLE Buildings (
    building_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    building_name VARCHAR(20) NOT NULL,
    road_address VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_buildings_manager UNIQUE (user_id),
    CONSTRAINT fk_buildings_manager FOREIGN KEY (user_id) REFERENCES Users(user_id)
);

CREATE TABLE Rooms (
    room_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    building_id BIGINT NOT NULL,
    user_id BIGINT NULL,
    room_status VARCHAR(20) NOT NULL,
    room_no VARCHAR(5) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_rooms_building_room_no UNIQUE (building_id, room_no),
    CONSTRAINT uk_rooms_resident UNIQUE (user_id),
    CONSTRAINT fk_rooms_building FOREIGN KEY (building_id) REFERENCES Buildings(building_id),
    CONSTRAINT fk_rooms_resident FOREIGN KEY (user_id) REFERENCES Users(user_id)
);

CREATE TABLE Invitation_codes (
    code_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    code CHAR(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_invitation_codes_code UNIQUE (code),
    CONSTRAINT fk_invitation_codes_room FOREIGN KEY (room_id) REFERENCES Rooms(room_id)
);

CREATE TABLE Files (
    attachment_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    file_key VARCHAR(255) NOT NULL,
    file_size INT NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    file_status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL
);

CREATE TABLE Rule_Documents (
    document_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    building_id BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL,
    document_title VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    is_valid BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_rule_documents_building FOREIGN KEY (building_id) REFERENCES Buildings(building_id),
    CONSTRAINT fk_rule_documents_file FOREIGN KEY (attachment_id) REFERENCES Files(attachment_id)
);

CREATE TABLE Conversations (
    conversation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_type VARCHAR(20) NOT NULL,
    conversation_status VARCHAR(20) NOT NULL,
    conversation_title VARCHAR(100) NULL,
    last_message_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES Users(user_id),
    -- 본인 대화 목록을 최근순으로 조회한다.
    INDEX idx_conversations_user_latest (user_id, deleted_at, last_message_at)
);

CREATE TABLE Messages (
    message_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    content VARCHAR(200) NOT NULL,
    sender_type VARCHAR(10) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES Conversations(conversation_id),
    -- 대화 상세를 message_id 커서로 페이징한다.
    INDEX idx_messages_conversation_cursor (conversation_id, deleted_at, message_id)
);

CREATE TABLE Message_file_groups (
    file_group_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    attachment_id BIGINT NOT NULL,
    file_group_seq INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_message_file_groups_seq UNIQUE (message_id, file_group_seq),
    CONSTRAINT ck_message_file_groups_seq CHECK (file_group_seq BETWEEN 1 AND 3),
    CONSTRAINT fk_message_file_groups_message FOREIGN KEY (message_id) REFERENCES Messages(message_id),
    CONSTRAINT fk_message_file_groups_file FOREIGN KEY (attachment_id) REFERENCES Files(attachment_id)
);

CREATE TABLE Complaints (
    complaint_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    building_id BIGINT NOT NULL,
    attachment_id BIGINT NULL,
    title VARCHAR(50) NOT NULL,
    complaint_status VARCHAR(20) NOT NULL,
    urgency TINYINT NOT NULL,
    room_no VARCHAR(5) NOT NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    -- 대화 하나당 민원 하나. 중복 접수의 최종 방어선이다.
    CONSTRAINT uk_complaints_conversation UNIQUE (conversation_id),
    -- 민원 대표 이미지 1:1 매핑
    CONSTRAINT uk_complaints_attachment UNIQUE (attachment_id),
    CONSTRAINT fk_complaints_conversation FOREIGN KEY (conversation_id) REFERENCES Conversations(conversation_id),
    CONSTRAINT fk_complaints_user FOREIGN KEY (user_id) REFERENCES Users(user_id),
    CONSTRAINT fk_complaints_building FOREIGN KEY (building_id) REFERENCES Buildings(building_id),
    CONSTRAINT fk_complaints_file FOREIGN KEY (attachment_id) REFERENCES Files(attachment_id),
    -- 관리자 민원 목록 조회
    INDEX idx_complaints_building_status (building_id, deleted_at, complaint_status)
);

CREATE TABLE Complaint_Details (
    complaint_id BIGINT NOT NULL PRIMARY KEY,
    location VARCHAR(50) NOT NULL,
    symptom VARCHAR(100) NOT NULL,
    occurred_time VARCHAR(50) NOT NULL,
    ai_summary VARCHAR(200) NOT NULL,
    comment VARCHAR(200) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_complaint_details_complaint FOREIGN KEY (complaint_id) REFERENCES Complaints(complaint_id)
);

CREATE TABLE Notifications (
    noti_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL
);

CREATE TABLE User_notifications (
    user_noti_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    noti_id BIGINT NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_user_notifications_user FOREIGN KEY (user_id) REFERENCES Users(user_id),
    CONSTRAINT fk_user_notifications_noti FOREIGN KEY (noti_id) REFERENCES Notifications(noti_id),
    -- 수신함 조회
    INDEX idx_user_notifications_user (user_id, deleted_at, user_noti_id)
);
