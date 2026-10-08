-- V95__reward_alert_notified_on.sql
-- The business date (IST) a reward alert marker was recorded, so the inbox can show recent
-- milestones and exhausted caps by when they happened rather than by the window's start
-- (a yearly window's start is months old the day its cap is used up).
--   reward_milestones                 : notified_on
--   reward_rules / reward_cap_buckets : cap_notified_on
-- Nullable: rows marked before this migration keep NULL and the inbox falls back to the window
-- start for them.
--
-- Idempotent: every DDL swallows ORA-01430 (column already exists).

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
    run_ddl_swallowing('ALTER TABLE reward_milestones ADD notified_on DATE', -1430);
    run_ddl_swallowing('ALTER TABLE reward_rules ADD cap_notified_on DATE', -1430);
    run_ddl_swallowing('ALTER TABLE reward_cap_buckets ADD cap_notified_on DATE', -1430);
END;
/
