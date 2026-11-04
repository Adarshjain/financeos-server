-- V153__app_notifications_cleared_by.sql
-- Why a feed row was cleared: USER (the user cleared it) or AUTO (a reminder resolved because the bill was paid,
-- the EMI recorded, ...). An AUTO-cleared reminder comes back when it is due again; a USER-cleared one stays hidden
-- until OVERDUE. Also a function index for the feed order, coalesce(updated_at, created_at).
--
-- Idempotent: swallows ORA-01430 (column exists), ORA-00955 / ORA-01408 (index exists).

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
    run_ddl_swallowing('ALTER TABLE app_notifications ADD (cleared_by VARCHAR2(10))', -1430);
    run_ddl_swallowing('CREATE INDEX idx_app_notifications_user_surfaced ON app_notifications (user_id, COALESCE(updated_at, created_at))', -955, -1408);
END;
/
