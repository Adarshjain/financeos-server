-- V94__inbox_item_state.sql
-- The inbox is computed on read from the producers' own rows (bills, EMIs, lendings, statements,
-- Gmail, review queue, jobs, rewards); nothing is stored for the rows themselves. The only state
-- the user adds is "snooze until" and "dismissed", keyed by the row's stable item key
-- (bill:<statementId>, emi:<loanId>:<seq>, ...), one row per user and key.
--
-- Idempotent: the DDL swallows ORA-00955 (object already exists).

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
    run_ddl_swallowing('CREATE TABLE inbox_item_state (
        id             VARCHAR2(36) PRIMARY KEY,
        user_id        VARCHAR2(36) NOT NULL,
        item_key       VARCHAR2(200) NOT NULL,
        snoozed_until  DATE,
        dismissed_at   TIMESTAMP WITH TIME ZONE,
        created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_inbox_item_state_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT uq_inbox_item_state_user_key UNIQUE (user_id, item_key)
    )', -955);
END;
/
