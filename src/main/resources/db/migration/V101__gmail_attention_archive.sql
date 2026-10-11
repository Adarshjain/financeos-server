-- V101__gmail_attention_archive.sql
-- Lets the user archive a "needs attention" Gmail item: archived_at hides the row from the
-- Connections page list, the Inbox count and the attention push digest. Cleared whenever the
-- row's status changes (retry / reactivation), so a fresh failure shows up again.
--
-- Idempotent: the DDL swallows ORA-01430 (column already exists).

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE gmail_processed_messages ADD archived_at TIMESTAMP WITH TIME ZONE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -1430 THEN
            RAISE;
        END IF;
END;
/
