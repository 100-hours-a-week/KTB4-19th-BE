ALTER TABLE Rule_document_indexing_outbox
    ADD COLUMN updated_at DATETIME(6) NULL AFTER created_at,
    ADD COLUMN deleted_at DATETIME(6) NULL AFTER updated_at;

UPDATE Rule_document_indexing_outbox
SET updated_at = COALESCE(processed_at, created_at)
WHERE updated_at IS NULL;

ALTER TABLE Rule_document_indexing_outbox
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
