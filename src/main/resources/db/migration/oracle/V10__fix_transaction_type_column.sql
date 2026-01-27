-- Fix transaction type column to use VARCHAR2 (consistent with V3 enum-to-text conversion)
-- V9 already created the column correctly as VARCHAR2 for Oracle
-- This migration ensures consistency if V9 was run with enum type by mistake

-- Drop the type column if it exists
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP COLUMN type';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -904 THEN -- ORA-00904: invalid identifier
            RAISE;
        END IF;
END;
/

-- Add type column as VARCHAR2 (consistent with source, account.type, etc.)
ALTER TABLE transactions ADD type VARCHAR2(10);

-- Migrate existing data: negative = DEBIT, positive = CREDIT
UPDATE transactions 
SET type = CASE 
    WHEN amount < 0 THEN 'DEBIT'
    ELSE 'CREDIT'
END;

-- Make amounts absolute (if not already done)
UPDATE transactions SET amount = ABS(amount) WHERE amount < 0;

-- Make type NOT NULL
ALTER TABLE transactions MODIFY type VARCHAR2(10) NOT NULL;

-- Add check constraint to ensure only valid values
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD CONSTRAINT chk_transactions_type CHECK (type IN (''DEBIT'', ''CREDIT''))';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -2264 THEN -- ORA-02264: name already used by an existing constraint
            RAISE;
        END IF;
END;
/

-- Add index on type for filtering (if not exists)
BEGIN
    EXECUTE IMMEDIATE 'CREATE INDEX idx_transactions_type ON transactions(type)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -955 THEN -- ORA-00955: name is already used by an existing object
            RAISE;
        END IF;
END;
/

-- Commit the changes
COMMIT;
