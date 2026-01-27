-- Fix transaction type column to use TEXT (consistent with V3 enum-to-text conversion)
-- V9 created the column with transaction_type enum, but V3 established pattern of using TEXT

-- Drop the type column if it exists
ALTER TABLE transactions DROP COLUMN IF EXISTS type;

-- Add type column as TEXT (consistent with source, account.type, etc.)
ALTER TABLE transactions ADD COLUMN type TEXT;

-- Migrate existing data: negative = DEBIT, positive = CREDIT
UPDATE transactions 
SET type = CASE 
    WHEN amount < 0 THEN 'DEBIT'
    ELSE 'CREDIT'
END;

-- Make amounts absolute (if not already done)
UPDATE transactions SET amount = ABS(amount) WHERE amount < 0;

-- Make type NOT NULL
ALTER TABLE transactions ALTER COLUMN type SET NOT NULL;

-- Add check constraint to ensure only valid values
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check CHECK (type IN ('DEBIT', 'CREDIT'));

-- Add index on type for filtering
CREATE INDEX IF NOT EXISTS idx_transactions_type ON transactions(type);
