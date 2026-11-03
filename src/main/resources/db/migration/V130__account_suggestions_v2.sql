-- V130__account_suggestions_v2.sql
-- Account suggestions, second pass: the kind's confidence (a LOW kind is only ever a "maybe") and whether the user
-- answered "no" to "Is this your...", so that question is never asked again for the row.
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
    run_ddl_swallowing('ALTER TABLE account_suggestions ADD (kind_conf VARCHAR2(10))', -1430);
    run_ddl_swallowing('ALTER TABLE account_suggestions ADD (is_this_declined NUMBER(1) DEFAULT 0 NOT NULL)', -1430);
END;
/
