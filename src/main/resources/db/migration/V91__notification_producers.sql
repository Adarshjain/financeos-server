-- V91__notification_producers.sql
-- Notification producers beyond card bills. Same principle as V90: no notification table; each
-- producer keeps its idempotency marker on its own row.
--   gmail_connections       : a refresh token Google rejected (auth_failed_at) and when the user was
--                             last told to reconnect (reconnect_notified_at; weekly re-nag).
--   gmail_processed_messages: when an attention item (unmatched account / not opted in / permanent
--                             failure) was included in a "needs attention" digest.
--   loans                   : per-loan mute (autopay) and the EMI reminder marker — the current
--                             unsettled installment (seq) plus the last kind/date sent for it.
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
    run_ddl_swallowing('ALTER TABLE gmail_connections ADD auth_failed_at TIMESTAMP WITH TIME ZONE', -1430);
    run_ddl_swallowing('ALTER TABLE gmail_connections ADD reconnect_notified_at TIMESTAMP WITH TIME ZONE', -1430);

    run_ddl_swallowing('ALTER TABLE gmail_processed_messages ADD attention_notified_at TIMESTAMP WITH TIME ZONE', -1430);

    run_ddl_swallowing('ALTER TABLE loans ADD notifications_muted NUMBER(1) DEFAULT 0 NOT NULL', -1430);
    run_ddl_swallowing('ALTER TABLE loans ADD last_notified_seq NUMBER(10)', -1430);
    run_ddl_swallowing('ALTER TABLE loans ADD last_notified_kind VARCHAR2(20)', -1430);
    run_ddl_swallowing('ALTER TABLE loans ADD last_notified_on DATE', -1430);
END;
/
