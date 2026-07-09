-- Create category_rules table
CREATE TABLE category_rules (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36) NOT NULL,
    merchant_key VARCHAR2(255) NOT NULL,
    display_name VARCHAR2(255) NULL,
    verified NUMBER(1) DEFAULT 0 NOT NULL,
    source VARCHAR2(20) NOT NULL,
    applied_count NUMBER(10) DEFAULT 0 NOT NULL,
    last_applied_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_category_rules_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_category_rules_user_merchant UNIQUE (user_id, merchant_key)
);

CREATE INDEX idx_category_rules_user ON category_rules(user_id);

-- Create category_rule_categories join table
CREATE TABLE category_rule_categories (
    rule_id VARCHAR2(36) NOT NULL,
    category_id VARCHAR2(36) NOT NULL,
    PRIMARY KEY (rule_id, category_id),
    CONSTRAINT fk_rule_cats_rule FOREIGN KEY (rule_id) REFERENCES category_rules(id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_cats_cat FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
);

-- Create transaction_review_reasons table
CREATE TABLE transaction_review_reasons (
    transaction_id VARCHAR2(36) NOT NULL,
    reason VARCHAR2(30) NOT NULL,
    PRIMARY KEY (transaction_id, reason),
    CONSTRAINT fk_rev_reasons_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
);

-- Add applied_rule_id to transactions
ALTER TABLE transactions ADD applied_rule_id VARCHAR2(36) NULL;
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_applied_rule FOREIGN KEY (applied_rule_id) REFERENCES category_rules(id) ON DELETE SET NULL;

-- Backfill transaction_review_reasons
INSERT INTO transaction_review_reasons (transaction_id, reason)
SELECT id, CASE WHEN source IN ('gmail_transaction_alert', 'gmail_statement') THEN 'UNRECONCILED' ELSE 'OTHER' END
FROM transactions
WHERE review_type = 'NEEDS_REVIEW';
