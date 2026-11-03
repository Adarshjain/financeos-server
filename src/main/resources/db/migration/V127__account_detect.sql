-- V127__account_detect.sql
-- Background account detection (ACCOUNT_DETECT job). account_detect_state: one row per user (scan status, the
-- newest mail read, progress, the user's hashing salt for layout keys, encrypted). account_detect_facts: per mail the
-- detector's derived features only (facts is encrypted JSON; never subject, snippet, body or PDF text) plus the
-- statement-PDF bookkeeping a later unlock step needs. Both cascade with the user; Gmail disconnect deletes them.
--
-- Idempotent: swallows ORA-00955 (name already used) and ORA-01408 (column list already indexed).

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
    run_ddl_swallowing('CREATE TABLE account_detect_state (
        user_id             VARCHAR2(36) NOT NULL,
        connection_id       VARCHAR2(36),
        status              VARCHAR2(20) NOT NULL,
        marker_at           TIMESTAMP(6) WITH TIME ZONE,
        first_scan_done_at  TIMESTAMP(6) WITH TIME ZONE,
        last_run_at         TIMESTAMP(6) WITH TIME ZONE,
        last_error          VARCHAR2(40),
        mails_seen          NUMBER(10) DEFAULT 0 NOT NULL,
        mails_total         NUMBER(10) DEFAULT 0 NOT NULL,
        key_salt            VARCHAR2(200),
        updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
        CONSTRAINT pk_account_detect_state PRIMARY KEY (user_id),
        CONSTRAINT fk_acct_detect_state_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT ck_acct_detect_state_status CHECK (status IN (''IDLE'', ''RUNNING'', ''PAUSED_AUTH'', ''FAILED''))
    )', -955);

    run_ddl_swallowing('CREATE TABLE account_detect_facts (
        id                  VARCHAR2(36) PRIMARY KEY,
        user_id             VARCHAR2(36) NOT NULL,
        message_id          VARCHAR2(64) NOT NULL,
        internal_date       TIMESTAMP(6) WITH TIME ZONE,
        sender_domain       VARCHAR2(255),
        role                VARCHAR2(30),
        facts               CLOB NOT NULL,
        has_pdf             NUMBER(1) DEFAULT 0 NOT NULL,
        pdf_locked          NUMBER(1) DEFAULT 0 NOT NULL,
        pdf_attachment_id   VARCHAR2(4000),
        unlock_state        VARCHAR2(20) DEFAULT ''NONE'' NOT NULL,
        created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_acct_detect_facts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT uq_acct_detect_facts_msg UNIQUE (user_id, message_id),
        CONSTRAINT ck_acct_detect_facts_unlock CHECK (unlock_state IN (''NONE'', ''LOCKED'', ''UNLOCKED'', ''FAILED''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_acct_detect_facts_date ON account_detect_facts (user_id, internal_date)', -955, -1408);
END;
/
