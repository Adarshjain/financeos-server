-- Flyway Migration V34: Oracle Index & Constraint Optimizations

-- 1. Safely drop old global unique index if it exists
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_indexes WHERE index_name = 'UK_TXN_SOURCE_MSG';
    IF v_count > 0 THEN
        EXECUTE IMMEDIATE 'DROP INDEX uk_txn_source_msg';
    END IF;
END;
/

-- 2. Create user-scoped multi-tenant composite unique index
CREATE UNIQUE INDEX uk_txn_user_source_msg ON transactions (CASE WHEN source_message_id IS NOT NULL THEN user_id END, source_message_id);

-- 3. Add composite indexes for common transaction query access patterns
CREATE INDEX idx_txn_user_account_date ON transactions (user_id, account_id, transaction_date DESC, id DESC);
CREATE INDEX idx_txn_user_review_date ON transactions (user_id, review_type, transaction_date DESC);

-- 4. Index review reasons for fast review filtering (eliminates full table scans)
CREATE INDEX idx_rev_reasons_reason ON transaction_review_reasons (reason, transaction_id);

-- 5. Foreign key index on applied_rule_id to prevent parent update locks
CREATE INDEX idx_txn_applied_rule ON transactions (applied_rule_id);

-- 6. Deduplicate statements per user using SHA-256 composite unique index
CREATE UNIQUE INDEX uk_stmt_user_sha256 ON statements (CASE WHEN file_sha256 IS NOT NULL THEN user_id END, file_sha256);
