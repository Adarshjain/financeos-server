-- V131__account_suggestions_parent_declined.sql
-- Whether the user answered "not part of that account" to "Older number on X?", so the row is never linked to an
-- account as an older number again.
--
-- Idempotent: swallows ORA-01430 (column already exists).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT 0) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE NOT IN (p_err1, p_err2) THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('ALTER TABLE account_suggestions ADD (parent_declined NUMBER(1) DEFAULT 0 NOT NULL)', -1430);
END;
/
