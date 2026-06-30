-- Update existing source values from 'gmail' to 'gmail_transaction_alert'
UPDATE transactions SET source = 'gmail_transaction_alert' WHERE source = 'gmail';

-- Recreate transactions check constraint to restrict to the new allowed sources
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_source;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_source
  CHECK (source IN ('gmail_transaction_alert', 'gmail_statement', 'manual'));

-- Add statement_password to account_bank_details table (encrypted at application layer)
ALTER TABLE account_bank_details ADD statement_password VARCHAR2(500) NULL;
