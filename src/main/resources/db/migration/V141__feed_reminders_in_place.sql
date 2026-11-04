-- V141__feed_reminders_in_place.sql
-- Feed reminders (card bills, EMI installments) update their one row in place: updated_at when the text last
-- changed, stage = the reminder stage the row shows (D3/D2/D1/D0/OVERDUE) so a stage change re-surfaces it, and
-- loan_id so an EMI row can link to its loan (a plain reference like account_id, no FK).
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
    run_ddl_swallowing('ALTER TABLE app_notifications ADD (updated_at TIMESTAMP WITH TIME ZONE)', -1430);
    run_ddl_swallowing('ALTER TABLE app_notifications ADD (stage VARCHAR2(20))', -1430);
    run_ddl_swallowing('ALTER TABLE app_notifications ADD (loan_id VARCHAR2(36))', -1430);
END;
/
