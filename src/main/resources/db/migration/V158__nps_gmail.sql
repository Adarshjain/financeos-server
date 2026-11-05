-- NPS from Gmail: gmail_senders.document_sender flags a sender whose mails a document handler reads (its Gmail
-- backfill uses the sender's own floor, not the user's), nps_gmail_pending holds a statement mail that needs the
-- user (its password, or a decision on a PRAN that is not an account yet), and the NPS senders go to every user
-- that already has a Gmail connection (new connections get them when they connect).
--
-- Idempotent: the DDL swallows ORA-01430 (column already exists), ORA-00955 (object already exists) and
-- ORA-01408 (column list already indexed); the sender insert adds only addresses a user does not have yet.

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
    run_ddl_swallowing('ALTER TABLE gmail_senders ADD document_sender NUMBER(1) DEFAULT 0 NOT NULL', -1430);

    run_ddl_swallowing('CREATE TABLE nps_gmail_pending (
        id                 VARCHAR2(36) PRIMARY KEY,
        user_id            VARCHAR2(36) NOT NULL,
        connection_id      VARCHAR2(36),
        gmail_message_id   VARCHAR2(200) NOT NULL,
        reason             VARCHAR2(12) NOT NULL,
        pran_last4         VARCHAR2(4),
        holder_name        VARCHAR2(200 CHAR),
        period_end         DATE,
        password_encrypted VARCHAR2(500),
        created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_nps_gmail_pending_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_gmail_pending_conn FOREIGN KEY (connection_id) REFERENCES gmail_connections(id) ON DELETE CASCADE,
        CONSTRAINT ck_nps_gmail_pending_reason CHECK (reason IN (''PASSWORD'', ''NEW_ACCOUNT''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_gmail_pending_user ON nps_gmail_pending (user_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_gmail_pending_conn ON nps_gmail_pending (connection_id)', -955, -1408);
    run_ddl_swallowing('CREATE UNIQUE INDEX uq_nps_gmail_pending_msg ON nps_gmail_pending (user_id, gmail_message_id)',
        -955, -1408);

    FOR r IN (
        SELECT 'nps-statements@mailer.proteantech.in' AS addr FROM dual UNION ALL
        SELECT 'nps-statements@proteantech.in' FROM dual UNION ALL
        SELECT 'cra@nsdl.co.in' FROM dual UNION ALL
        SELECT 'nps-alerts@proteantech.in' FROM dual UNION ALL
        SELECT 'cra-alerts@nsdl.co.in' FROM dual UNION ALL
        SELECT 'enps-alerts@proteantech.in' FROM dual
    ) LOOP
        EXECUTE IMMEDIATE 'INSERT INTO gmail_senders (id, user_id, name, sender_address, enabled, created_at, updated_at, '
            || 'backfill_from, document_sender) '
            || 'SELECT LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), ''([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})'', ''\1-\2-\3-\4-\5'')), '
            || 'c.user_id, ''NPS (Protean)'', :addr, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, DATE ''2013-01-01'', 1 '
            || 'FROM (SELECT DISTINCT user_id FROM gmail_connections) c '
            || 'WHERE NOT EXISTS (SELECT 1 FROM gmail_senders s WHERE s.user_id = c.user_id AND LOWER(s.sender_address) = :addr2)'
            USING r.addr, r.addr;
    END LOOP;
END;
/
