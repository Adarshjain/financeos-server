-- Recreate transactions check constraint to restrict to the new allowed sources including file_upload
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_source;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_source
  CHECK (source IN ('gmail_transaction_alert', 'gmail_statement', 'manual', 'file_upload'));
