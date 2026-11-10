-- Back-fill of the original file for Gmail statements ingested before files were kept: when the back-fill last tried a
-- statement and how that ended. Final outcomes (ATTACHED, MAIL_GONE, NO_MATCHING_ATTACHMENT, NOT_STORED) are never
-- selected again; retryable ones (NO_CONNECTION, ERROR, STORE_UNAVAILABLE) are stamped too and selected again only
-- after a retry window.
--
-- Idempotent: each column is added only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'FILE_BACKFILL_AT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (file_backfill_at TIMESTAMP WITH TIME ZONE NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'FILE_BACKFILL_OUTCOME';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (file_backfill_outcome VARCHAR2(30) NULL)';
    END IF;
END;
/
