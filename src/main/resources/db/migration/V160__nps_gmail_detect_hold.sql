-- NPS from Gmail, detect and hold: a statement of a PRAN that is no account yet is held (not imported, no account
-- created) until the user says go. nps_gmail_pending keeps what is needed to import it later without guessing: the
-- full PRAN (encrypted), the period start, the POP name (for the sector guess), the password that opened it
-- (password_encrypted, already there) and a status (PENDING / IGNORED / DONE) instead of deleting rows, plus the
-- reason an import attempt failed and how many attempts were made (a statement that failed 3 times waits for the
-- user's retry). An account may now have no sector yet (the card shows "Not set").
--
-- Idempotent: the DDL swallows ORA-01430 (column already exists), ORA-02264 / ORA-02275 (constraint already exists)
-- and ORA-01451 (column already nullable). last_tried_at orders the password unlock batch.

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
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD pran_encrypted VARCHAR2(500)', -1430);
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD period_start DATE', -1430);
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD pop_name VARCHAR2(200 CHAR)', -1430);
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD status VARCHAR2(8) DEFAULT ''PENDING'' NOT NULL', -1430);
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD last_error VARCHAR2(300 CHAR)', -1430);
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD attempts NUMBER(3) DEFAULT 0 NOT NULL', -1430);
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD CONSTRAINT ck_nps_gmail_pending_status '
        || 'CHECK (status IN (''PENDING'', ''IGNORED'', ''DONE''))', -2264, -2275);
    run_ddl_swallowing('ALTER TABLE nps_gmail_pending ADD last_tried_at TIMESTAMP WITH TIME ZONE', -1430);
    run_ddl_swallowing('ALTER TABLE nps_account MODIFY sector NULL', -1451);
    -- Tier I status from the newest statement (ACTIVE / FROZEN / OTHER) and the period end it came from.
    run_ddl_swallowing('ALTER TABLE nps_account ADD tier1_status VARCHAR2(20)', -1430);
    run_ddl_swallowing('ALTER TABLE nps_account ADD tier1_status_as_of DATE', -1430);
END;
/
