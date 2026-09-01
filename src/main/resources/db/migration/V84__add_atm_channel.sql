-- Flyway Migration V84: Add ATM to TransactionChannel check constraints

-- Drop old check constraints if present (swallowing ORA-02443: Cannot drop constraint - nonexistent constraint)
DECLARE
    PROCEDURE drop_constraint_if_exists(p_table VARCHAR2, p_constraint VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table || ' DROP CONSTRAINT ' || p_constraint;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -2443 THEN
                RAISE;
            END IF;
    END;
BEGIN
    drop_constraint_if_exists('TRANSACTIONS', 'CHK_TXN_CHANNEL');
    drop_constraint_if_exists('REWARD_RULE_CHANNELS', 'CHK_RRCH_CHANNEL');
END;
/

-- Add updated check constraints if not present (swallowing ORA-02264: name already used by an existing constraint)
DECLARE
    PROCEDURE add_constraint_if_not_exists(p_sql VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -2264 THEN
                RAISE;
            END IF;
    END;
BEGIN
    add_constraint_if_not_exists('ALTER TABLE transactions ADD CONSTRAINT chk_txn_channel CHECK (channel IN (''ONLINE'',''POS'',''UPI'',''CONTACTLESS'',''ATM'',''OTHER''))');
    add_constraint_if_not_exists('ALTER TABLE reward_rule_channels ADD CONSTRAINT chk_rrch_channel CHECK (channel IN (''ONLINE'',''POS'',''UPI'',''CONTACTLESS'',''ATM'',''OTHER''))');
END;
/
