-- Remove spent_for column
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP COLUMN spent_for';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -904 THEN -- ORA-00904: invalid identifier
            RAISE;
        END IF;
END;
/

-- Add is_transaction_excluded column
ALTER TABLE transactions ADD is_transaction_excluded NUMBER(1) DEFAULT 0 NOT NULL;

-- Add is_transaction_under_monitoring column
ALTER TABLE transactions ADD is_transaction_under_monitoring NUMBER(1) DEFAULT 0 NOT NULL;

-- Create indexes for filtering
CREATE INDEX idx_trans_excluded ON transactions(is_transaction_excluded);
CREATE INDEX idx_trans_monitoring ON transactions(is_transaction_under_monitoring);
