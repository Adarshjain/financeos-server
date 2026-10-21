-- V107__app_notifications.sql
-- In-app notifications feed: one persisted row per "what happened" (a failed import, a Gmail sync that
-- found transactions, a statement from Gmail, a disconnected mailbox, a reward unlocked). Separate from
-- push: push settings never gate the feed. dedupe_key makes every producer idempotent per user.
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
    run_ddl_swallowing('CREATE TABLE app_notifications (
        id             VARCHAR2(36) PRIMARY KEY,
        user_id        VARCHAR2(36) NOT NULL,
        kind           VARCHAR2(40) NOT NULL,
        title          VARCHAR2(200) NOT NULL,
        body           VARCHAR2(500),
        job_id         VARCHAR2(36),
        job_type       VARCHAR2(40),
        account_id     VARCHAR2(36),
        connection_id  VARCHAR2(36),
        dedupe_key     VARCHAR2(200) NOT NULL,
        created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        read_at        TIMESTAMP WITH TIME ZONE,
        cleared_at     TIMESTAMP WITH TIME ZONE,
        CONSTRAINT fk_app_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT uq_app_notifications_user_key UNIQUE (user_id, dedupe_key)
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_app_notifications_user_created ON app_notifications (user_id, created_at)', -955, -1408);
END;
/
