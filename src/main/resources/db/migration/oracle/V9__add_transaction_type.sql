-- Add transaction type column (DEBIT/CREDIT)
-- This replaces the signed amount approach with explicit type + unsigned amount

-- Add type column as VARCHAR2 (nullable initially for migration)
ALTER TABLE transactions ADD type VARCHAR2(10);

-- Migrate existing data: negative = DEBIT, positive = CREDIT
-- Note: This assumes no zero amounts exist (they shouldn't)
UPDATE transactions 
SET type = CASE 
    WHEN amount < 0 THEN 'DEBIT'
    ELSE 'CREDIT'
END;

-- Make amounts absolute
UPDATE transactions SET amount = ABS(amount);

-- Make type NOT NULL
ALTER TABLE transactions MODIFY type VARCHAR2(10) NOT NULL;

-- Add check constraint to ensure only valid values
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_type CHECK (type IN ('DEBIT', 'CREDIT'));

-- Add check constraint for positive amounts (no zero allowed)
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0);

-- Add index on type for filtering
CREATE INDEX idx_transactions_type ON transactions(type);

-- Commit the changes
COMMIT;
