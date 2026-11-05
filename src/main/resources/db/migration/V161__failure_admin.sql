-- V161__failure_admin.sql
-- Admin Failures tool: the full stack trace of a failed job / failed Gmail message, and an admin "dismissed" mark
-- (set when an item is retried, skipped or dismissed by an admin). Nothing routes on these columns except the tool.
-- gmail_processed_messages.status has no CHECK constraint, so the new value SKIPPED_BY_ADMIN needs no DDL.
--
-- Idempotent: each ADD COLUMN swallows ORA-01430 (column already in the table).

DECLARE
    PROCEDURE add_column(p_sql VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -1430 THEN
                RAISE;
            END IF;
    END;
BEGIN
    add_column('ALTER TABLE jobs ADD error_detail CLOB');
    add_column('ALTER TABLE jobs ADD dismissed_at TIMESTAMP WITH TIME ZONE');
    add_column('ALTER TABLE jobs ADD dismissed_by VARCHAR2(320)');
    add_column('ALTER TABLE jobs ADD dismiss_reason VARCHAR2(500)');
    add_column('ALTER TABLE gmail_processed_messages ADD error_detail CLOB');
    add_column('ALTER TABLE gmail_processed_messages ADD dismissed_at TIMESTAMP WITH TIME ZONE');
    add_column('ALTER TABLE gmail_processed_messages ADD dismissed_by VARCHAR2(320)');
    add_column('ALTER TABLE gmail_processed_messages ADD dismiss_reason VARCHAR2(500)');
END;
/
