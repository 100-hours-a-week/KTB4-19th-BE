ALTER TABLE Files ADD COLUMN user_id BIGINT NULL;

ALTER TABLE Files
    ADD CONSTRAINT fk_files_user FOREIGN KEY (user_id) REFERENCES Users (user_id);

CREATE INDEX idx_files_user_id ON Files (user_id);
