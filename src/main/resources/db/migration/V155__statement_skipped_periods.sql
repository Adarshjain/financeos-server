-- Billing cycles of a credit card the owner marked "No statement this period" (some banks send no statement for a cycle
-- without transactions). A skipped cycle counts as covered for every statement-expected projection (Inbox row, Upcoming,
-- bills badge). One row per (account, period_end); both foreign keys cascade and are indexed.
--
-- Idempotent: the table and indexes are created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'STATEMENT_SKIPPED_PERIODS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE statement_skipped_periods (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            account_id VARCHAR2(36) NOT NULL,
            period_start DATE NOT NULL,
            period_end DATE NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT fk_ssp_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_ssp_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
            CONSTRAINT uk_ssp_account_end UNIQUE (account_id, period_end)
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'STATEMENT_SKIPPED_PERIODS' AND column_name = 'ACCOUNT_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_ssp_account ON statement_skipped_periods (account_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'STATEMENT_SKIPPED_PERIODS' AND column_name = 'USER_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_ssp_user ON statement_skipped_periods (user_id)';
    END IF;
END;
/
