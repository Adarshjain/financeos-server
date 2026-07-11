-- Add updated_at and reviewed_at columns to transactions
ALTER TABLE transactions ADD updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE transactions ADD reviewed_at TIMESTAMP WITH TIME ZONE;

-- Enforce NOT NULL constraint on account_id
ALTER TABLE transactions MODIFY account_id VARCHAR2(36) NOT NULL;

-- Create index on user_id and review_type
CREATE INDEX idx_txn_user_review_type ON transactions (user_id, review_type);
