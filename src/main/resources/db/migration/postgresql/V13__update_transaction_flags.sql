-- Remove spent_for column
ALTER TABLE transactions DROP COLUMN IF EXISTS spent_for;

-- Add is_transaction_excluded column
ALTER TABLE transactions ADD COLUMN is_transaction_excluded BOOLEAN NOT NULL DEFAULT false;

-- Add is_transaction_under_monitoring column
ALTER TABLE transactions ADD COLUMN is_transaction_under_monitoring BOOLEAN NOT NULL DEFAULT false;

-- Create indexes for filtering
CREATE INDEX idx_transactions_excluded ON transactions(is_transaction_excluded);
CREATE INDEX idx_transactions_monitoring ON transactions(is_transaction_under_monitoring);
