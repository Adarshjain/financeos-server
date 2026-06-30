-- Add review_type and source_message_id to transactions table
ALTER TABLE transactions ADD review_type VARCHAR2(32) NULL;
ALTER TABLE transactions ADD source_message_id VARCHAR2(255) NULL;
CREATE UNIQUE INDEX uk_txn_source_msg ON transactions (source_message_id);
