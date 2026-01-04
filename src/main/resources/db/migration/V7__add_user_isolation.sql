-- Add user_id to accounts
ALTER TABLE accounts ADD COLUMN user_id UUID;
ALTER TABLE accounts ADD CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Add user_id to transactions
ALTER TABLE transactions ADD COLUMN user_id UUID;
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Add user_id to investment_transactions
ALTER TABLE investment_transactions ADD COLUMN user_id UUID;
ALTER TABLE investment_transactions ADD CONSTRAINT fk_investment_transactions_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Add user_id to gmail_sync_state
ALTER TABLE gmail_sync_state ADD COLUMN user_id UUID;
ALTER TABLE gmail_sync_state ADD CONSTRAINT fk_gmail_sync_state_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Add user_id to account_bank_details
ALTER TABLE account_bank_details ADD COLUMN user_id UUID;
ALTER TABLE account_bank_details ADD CONSTRAINT fk_account_bank_details_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Add user_id to account_credit_card_details
ALTER TABLE account_credit_card_details ADD COLUMN user_id UUID;
ALTER TABLE account_credit_card_details ADD CONSTRAINT fk_account_credit_card_details_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Add user_id to account_mutual_fund_details
ALTER TABLE account_mutual_fund_details ADD COLUMN user_id UUID;
ALTER TABLE account_mutual_fund_details ADD CONSTRAINT fk_account_mutual_fund_details_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Add user_id to account_stock_details
ALTER TABLE account_stock_details ADD COLUMN user_id UUID;
ALTER TABLE account_stock_details ADD CONSTRAINT fk_account_stock_details_user FOREIGN KEY (user_id) REFERENCES users(id);

-- Create indexes for performance
CREATE INDEX idx_accounts_user_id ON accounts(user_id);
CREATE INDEX idx_transactions_user_id ON transactions(user_id);
CREATE INDEX idx_investment_transactions_user_id ON investment_transactions(user_id);
