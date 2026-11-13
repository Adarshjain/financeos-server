-- V187__broker_holding_snapshots_and_scheme_checks.sql
-- The latest demat holdings file the user uploaded per broker account (every row, including stocks the app has not
-- seen), the per-account agreement of the company notices with the user's holdings for a demerger or merger, and the
-- short reason a corporate action was applied automatically.
--
-- Idempotent: tables, indexes and the column are created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'BROKER_HOLDING_SNAPSHOTS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE broker_holding_snapshots (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            broker_account_id VARCHAR2(36) NOT NULL,
            isin VARCHAR2(20) NULL,
            symbol VARCHAR2(60) NULL,
            quantity NUMBER(19,4) NOT NULL,
            as_of_date DATE NOT NULL,
            created_at TIMESTAMP NOT NULL,
            CONSTRAINT fk_holding_snap_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_holding_snap_account FOREIGN KEY (broker_account_id) REFERENCES accounts(id) ON DELETE CASCADE
        )';
    END IF;
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_HOLDING_SNAP_USER_ACCOUNT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_holding_snap_user_account ON broker_holding_snapshots (user_id, broker_account_id)';
    END IF;
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_HOLDING_SNAP_ACCOUNT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_holding_snap_account ON broker_holding_snapshots (broker_account_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'CORPORATE_SCHEME_CHECKS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE corporate_scheme_checks (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            instrument_id VARCHAR2(36) NOT NULL,
            kind VARCHAR2(20) NOT NULL,
            ex_date DATE NULL,
            agrees NUMBER(1) DEFAULT 0 NOT NULL,
            checks_json VARCHAR2(4000) NULL,
            checked_at TIMESTAMP NOT NULL,
            CONSTRAINT fk_scheme_check_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_scheme_check_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
        )';
    END IF;
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SCHEME_CHECK_USER';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_scheme_check_user ON corporate_scheme_checks (user_id)';
    END IF;
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SCHEME_CHECK_INSTRUMENT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_scheme_check_instrument ON corporate_scheme_checks (instrument_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'CORPORATE_ACTION_SUGGESTIONS' AND column_name = 'APPLIED_BECAUSE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE corporate_action_suggestions ADD (applied_because VARCHAR2(600) NULL)';
    END IF;
END;
/
