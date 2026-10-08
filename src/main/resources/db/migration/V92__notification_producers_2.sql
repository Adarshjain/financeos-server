-- V92__notification_producers_2.sql
-- Phase 2/3 notification producers; markers on the producers' own rows, no notification table.
--   lendings            : return_notified_kind/on — DUE_0 → OVERDUE (weekly) for the entry whose
--                         expected return date defines the counterparty's outstanding obligation.
--   statements          : review_notified_on — the one-shot "N transactions didn't reconcile" digest.
--   jobs                : notified_at — the "job finished/failed" push for user-triggered jobs.
--   accounts            : reward_alerts_checked_on (daily rewards pass per card),
--                         statement_expected_notified_for (the projected period end already nagged about).
--   reward_milestones   : notified_window_start/kind — CLOSING or ACHIEVED per window.
--   reward_rules / reward_cap_buckets : cap_notified_window_start — cap exhausted once per window.
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
    run_ddl_swallowing('ALTER TABLE lendings ADD return_notified_kind VARCHAR2(20)', -1430);
    run_ddl_swallowing('ALTER TABLE lendings ADD return_notified_on DATE', -1430);

    run_ddl_swallowing('ALTER TABLE statements ADD review_notified_on DATE', -1430);

    run_ddl_swallowing('ALTER TABLE jobs ADD notified_at TIMESTAMP WITH TIME ZONE', -1430);

    run_ddl_swallowing('ALTER TABLE accounts ADD reward_alerts_checked_on DATE', -1430);
    run_ddl_swallowing('ALTER TABLE accounts ADD statement_expected_notified_for DATE', -1430);

    run_ddl_swallowing('ALTER TABLE reward_milestones ADD notified_window_start DATE', -1430);
    run_ddl_swallowing('ALTER TABLE reward_milestones ADD notified_kind VARCHAR2(20)', -1430);
    run_ddl_swallowing('ALTER TABLE reward_rules ADD cap_notified_window_start DATE', -1430);
    run_ddl_swallowing('ALTER TABLE reward_cap_buckets ADD cap_notified_window_start DATE', -1430);
END;
/
