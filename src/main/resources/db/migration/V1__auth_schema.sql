CREATE TABLE Users (
    user_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password VARCHAR(60) NOT NULL,
    user_name VARCHAR(7) NULL,
    phone VARCHAR(13) NULL,
    user_status VARCHAR(10) NOT NULL,
    user_role VARCHAR(10) NOT NULL,
    auth_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
);
CREATE TABLE User_agreements (
    agreement_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    terms_type VARCHAR(10) NOT NULL,
    is_agreed BOOLEAN NOT NULL,
    agreed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_agreements_user FOREIGN KEY (user_id) REFERENCES Users(user_id),
    INDEX idx_agreements_user (user_id, agreement_id)
);
CREATE TABLE Refresh_sessions (
    session_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_refresh_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES Users(user_id),
    INDEX idx_refresh_user (user_id)
);
