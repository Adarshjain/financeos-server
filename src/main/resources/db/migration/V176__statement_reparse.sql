-- Re-reading stored statements when the parser improves: which parser version a statement was last re-read with,
-- when, what came of it (outcome name) and a short summary (field names and counts only, never values), and how many attempts were made at that version.
-- All nullable, so the previous JAR keeps working.
--
-- Idempotent: each column is added only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'REPARSE_VERSION';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (reparse_version NUMBER(6) NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'REPARSE_AT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (reparse_at TIMESTAMP WITH TIME ZONE NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'REPARSE_OUTCOME';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (reparse_outcome VARCHAR2(30) NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'REPARSE_SUMMARY';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (reparse_summary VARCHAR2(500) NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'REPARSE_ATTEMPTS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (reparse_attempts NUMBER(3) NULL)';
    END IF;
END;
/
