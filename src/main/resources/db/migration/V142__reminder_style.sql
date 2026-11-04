-- V142__reminder_style.sql
-- Per-user reminder style: STANDARD (push at every offset, overdue re-nags) or QUIET (one push per bill/EMI,
-- no overdue re-nags; the feed row still updates in place).
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
    run_ddl_swallowing('ALTER TABLE user_notification_settings ADD (reminder_style VARCHAR2(10) DEFAULT ''STANDARD'' NOT NULL)', -1430);
END;
/
