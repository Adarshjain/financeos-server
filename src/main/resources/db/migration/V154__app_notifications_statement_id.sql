-- V154__app_notifications_statement_id.sql
-- statement_id so a bill reminder row can open its exact bill (a plain reference like loan_id, no FK: statements get
-- deleted and a stale id just opens the Statements tab).
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
    run_ddl_swallowing('ALTER TABLE app_notifications ADD (statement_id VARCHAR2(36))', -1430);
END;
/
