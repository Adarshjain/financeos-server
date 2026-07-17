-- Create statements table
CREATE TABLE statements (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36) NOT NULL,
    account_id VARCHAR2(36) NOT NULL,
    source VARCHAR2(20) NOT NULL,
    source_ref VARCHAR2(255),
    file_sha256 VARCHAR2(64),
    statement_type VARCHAR2(20),
    period_start DATE,
    period_end DATE,
    opening_balance NUMBER(19, 4),
    closing_balance NUMBER(19, 4),
    bank_name VARCHAR2(255),
    account_number_masked VARCHAR2(40),
    transaction_count NUMBER(10),
    lines_skipped NUMBER(10),
    total_debits NUMBER(19, 4),
    total_credits NUMBER(19, 4),
    parse_mode VARCHAR2(40),
    chain_validation_pct NUMBER(5, 2),
    checksum_ok NUMBER(1),
    verdict VARCHAR2(20),
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_statements_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_statements_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT uk_statements_account_period UNIQUE (account_id, period_start, period_end)
);

CREATE INDEX idx_statements_account ON statements(account_id);
CREATE INDEX idx_statements_user ON statements(user_id);

-- Credit card details table (1:1 with statements)
CREATE TABLE statement_credit_card_details (
    statement_id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36) NOT NULL,
    total_amount_due NUMBER(19, 4),
    minimum_amount_due NUMBER(19, 4),
    payment_due_date DATE,
    credit_limit NUMBER(19, 4),
    available_credit_limit NUMBER(19, 4),
    finance_charges NUMBER(19, 4),
    fees_and_charges NUMBER(19, 4),
    previous_balance NUMBER(19, 4),
    payments_received NUMBER(19, 4),
    total_purchases NUMBER(19, 4),
    reward_points_balance NUMBER(19, 4),
    reward_points_earned NUMBER(19, 4),
    CONSTRAINT fk_stmt_cc_statement FOREIGN KEY (statement_id) REFERENCES statements(id) ON DELETE CASCADE,
    CONSTRAINT fk_stmt_cc_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Join table linking statements to the transactions parsed from them
CREATE TABLE statement_transactions (
    statement_id VARCHAR2(36) NOT NULL,
    transaction_id VARCHAR2(36) NOT NULL,
    line_index NUMBER(10),
    balance_after NUMBER(19, 4),
    chain_valid NUMBER(1),
    PRIMARY KEY (statement_id, transaction_id),
    CONSTRAINT fk_stmt_txns_statement FOREIGN KEY (statement_id) REFERENCES statements(id) ON DELETE CASCADE,
    CONSTRAINT fk_stmt_txns_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
);

CREATE INDEX idx_stmt_txns_transaction ON statement_transactions(transaction_id);
