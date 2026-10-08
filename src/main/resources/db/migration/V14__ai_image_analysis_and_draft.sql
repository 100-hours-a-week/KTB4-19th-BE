ALTER TABLE Message_file_groups
    ADD COLUMN summary VARCHAR(100) NULL,
    ADD COLUMN ocr_text VARCHAR(200) NULL;

ALTER TABLE Conversations
    ADD COLUMN draft_issue_type VARCHAR(20) NULL,
    ADD COLUMN draft_attachment_ids VARCHAR(255) NULL,
    ADD COLUMN draft_representative_attachment_id BIGINT NULL;
