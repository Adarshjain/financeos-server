-- V128__user_profile_hello.sql
-- Onboarding phase 1: the name and birth date banks lock statement PDFs with, and the one-time Hello gate.
--   birth_date          encrypted "yyyy-MM-dd" (or "--MM-dd" when the year is unknown)
--   birth_date_source   TYPED | GOOGLE
--   hello_done_at       when the user finished the Hello screen (null = still to show)
--   terms_accepted_at   consent given on Hello
--   name_edited_at      set when the user edits the name; Google sign-in stops overwriting it
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
    add_column('ALTER TABLE users ADD birth_date VARCHAR2(500)');
    add_column('ALTER TABLE users ADD birth_date_source VARCHAR2(10)');
    add_column('ALTER TABLE users ADD hello_done_at TIMESTAMP WITH TIME ZONE');
    add_column('ALTER TABLE users ADD terms_accepted_at TIMESTAMP WITH TIME ZONE');
    add_column('ALTER TABLE users ADD name_edited_at TIMESTAMP WITH TIME ZONE');
END;
/
