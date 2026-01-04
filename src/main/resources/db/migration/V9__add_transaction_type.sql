-- Create transaction type enum
CREATE TYPE transaction_type AS ENUM ('DEBIT', 'CREDIT');

-- Add type column (nullable initially for migration)
ALTER TABLE transactions ADD COLUMN type transaction_type;

-- Migrate existing data: negative = DEBIT, positive = CREDIT
-- Note: This assumes no zero amounts exist (they shouldn't)
UPDATE transactions 
SET type = CASE 
    WHEN amount < 0 THEN 'DEBIT'::transaction_type
    ELSE 'CREDIT'::transaction_type
END;

-- Make amounts absolute
UPDATE transactions SET amount = ABS(amount);

-- Make type NOT NULL
ALTER TABLE transactions ALTER COLUMN type SET NOT NULL;

-- Add check constraint for positive amounts (no zero allowed)
ALTER TABLE transactions ADD CONSTRAINT transactions_amount_positive CHECK (amount > 0);

-- Add index on type for filtering
CREATE INDEX idx_transactions_type ON transactions(type);
