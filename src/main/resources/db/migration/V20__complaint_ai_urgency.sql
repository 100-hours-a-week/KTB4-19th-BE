ALTER TABLE Conversations
    ADD COLUMN draft_urgency TINYINT NULL,
    ADD COLUMN draft_is_urgent BOOLEAN NULL,
    ADD COLUMN draft_urgency_version VARCHAR(50) NULL;

ALTER TABLE Complaints
    MODIFY COLUMN urgency TINYINT NULL,
    ADD COLUMN is_urgent BOOLEAN NULL,
    ADD COLUMN urgency_version VARCHAR(50) NULL;

UPDATE Complaints SET urgency = NULL WHERE urgency = 0;
