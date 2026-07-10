-- Drop the existing ON DELETE SET NULL constraints
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_account;
ALTER TABLE investment_transactions DROP CONSTRAINT fk_inv_trans_account;

-- Recreate constraints with ON DELETE CASCADE
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
ALTER TABLE investment_transactions ADD CONSTRAINT fk_inv_trans_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;

-- Drop and recreate the processed messages foreign key with ON DELETE SET NULL
ALTER TABLE gmail_processed_messages DROP CONSTRAINT fk_gmail_proc_txn;
ALTER TABLE gmail_processed_messages ADD CONSTRAINT fk_gmail_proc_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL;
