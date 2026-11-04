-- Months an account's owner marked "Nothing happened that month" (the Statements tab month strip).
-- One row per (account, month); month is 'yyyy-mm'. Both foreign keys cascade and are indexed.
--
-- Idempotent: the table and indexes are created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'ACCOUNT_EMPTY_MONTHS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE account_empty_months (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            account_id VARCHAR2(36) NOT NULL,
            month CHAR(7) NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT fk_aem_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_aem_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
            CONSTRAINT uk_aem_account_month UNIQUE (account_id, month)
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'ACCOUNT_EMPTY_MONTHS' AND column_name = 'ACCOUNT_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_aem_account ON account_empty_months (account_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'ACCOUNT_EMPTY_MONTHS' AND column_name = 'USER_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_aem_user ON account_empty_months (user_id)';
    END IF;
END;
/
