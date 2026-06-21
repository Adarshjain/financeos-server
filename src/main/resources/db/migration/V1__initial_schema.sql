-- FinanceOS Initial Schema for Oracle
-- V1: Core tables for personal finance tracking with Oracle-specific syntax
-- Users table
CREATE TABLE users (
    id VARCHAR2(36) PRIMARY KEY,
    email VARCHAR2(255) UNIQUE NOT NULL,
    password_hash VARCHAR2(255),
    google_id VARCHAR2(255) UNIQUE,
    display_name VARCHAR2(255),
    picture_url VARCHAR2(1000),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Accounts table
CREATE TABLE accounts (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    name VARCHAR2(255) NOT NULL,
    type VARCHAR2(50) NOT NULL,
    exclude_from_net_asset NUMBER(1) DEFAULT 0 NOT NULL,
    financial_position VARCHAR2(50),
    description VARCHAR2(4000),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_accounts_type CHECK (type IN ('bank_account', 'credit_card', 'stock', 'mutual_fund', 'generic')),
    CONSTRAINT chk_accounts_position CHECK (financial_position IN ('asset', 'liability'))
);

CREATE INDEX idx_accounts_type ON accounts(type);
CREATE INDEX idx_accounts_financial_position ON accounts(financial_position);
CREATE INDEX idx_accounts_user_id ON accounts(user_id);

-- Bank account details table (1:1 with accounts)
CREATE TABLE account_bank_details (
    account_id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    opening_balance NUMBER(19, 4),
    last4 VARCHAR2(4),
    CONSTRAINT fk_bank_details_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_bank_details_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Credit card details table (1:1 with accounts)
CREATE TABLE account_credit_card_details (
    account_id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    last4 VARCHAR2(4) NOT NULL,
    credit_limit NUMBER(19, 4) NOT NULL,
    payment_due_day NUMBER(2) NOT NULL,
    grace_period_days NUMBER(3) NOT NULL,
    statement_password VARCHAR2(500),
    CONSTRAINT fk_cc_details_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_cc_details_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_cc_due_day CHECK (payment_due_day BETWEEN 1 AND 31),
    CONSTRAINT chk_cc_grace_period CHECK (grace_period_days >= 0)
);

-- Stock details table (1:1 with accounts)
CREATE TABLE account_stock_details (
    account_id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    instrument_code VARCHAR2(50) NOT NULL,
    last_traded_price NUMBER(19, 4),
    CONSTRAINT fk_stock_details_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_details_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_stock_details_instrument ON account_stock_details(instrument_code);

-- Mutual fund details table (1:1 with accounts)
CREATE TABLE account_mutual_fund_details (
    account_id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    instrument_code VARCHAR2(50) NOT NULL,
    last_traded_price NUMBER(19, 4),
    CONSTRAINT fk_mf_details_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_mf_details_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_mutual_fund_details_instrument ON account_mutual_fund_details(instrument_code);

-- Transactions table
CREATE TABLE transactions (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    account_id VARCHAR2(36),
    transaction_date DATE NOT NULL,
    amount NUMBER(19, 4) NOT NULL,
    description VARCHAR2(1000) NOT NULL,
    category VARCHAR2(255),
    subcategory VARCHAR2(255),
    spent_for VARCHAR2(255),
    source VARCHAR2(50) NOT NULL,
    type VARCHAR2(10) NOT NULL,
    metadata CLOB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_transactions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_transactions_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL,
    CONSTRAINT chk_transactions_source CHECK (source IN ('gmail', 'manual')),
    CONSTRAINT chk_transactions_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0),
    CONSTRAINT chk_metadata_json CHECK (metadata IS JSON)
);

CREATE INDEX idx_transactions_account ON transactions(account_id);
CREATE INDEX idx_transactions_date ON transactions(transaction_date);
CREATE INDEX idx_transactions_category ON transactions(category);
CREATE INDEX idx_transactions_source ON transactions(source);
CREATE INDEX idx_transactions_type ON transactions(type);
CREATE INDEX idx_transactions_user_id ON transactions(user_id);

-- Investment transactions table
CREATE TABLE investment_transactions (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    account_id VARCHAR2(36),
    type VARCHAR2(10) NOT NULL,
    quantity NUMBER(19, 8) NOT NULL,
    price NUMBER(19, 4) NOT NULL,
    transaction_date DATE NOT NULL,
    metadata CLOB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inv_trans_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_inv_trans_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL,
    CONSTRAINT chk_inv_trans_type CHECK (type IN ('buy', 'sell')),
    CONSTRAINT chk_inv_trans_quantity CHECK (quantity > 0),
    CONSTRAINT chk_inv_trans_price CHECK (price >= 0),
    CONSTRAINT chk_inv_metadata_json CHECK (metadata IS JSON)
);

CREATE INDEX idx_investment_transactions_account ON investment_transactions(account_id);
CREATE INDEX idx_investment_transactions_date ON investment_transactions(transaction_date);
CREATE INDEX idx_investment_transactions_type ON investment_transactions(type);
CREATE INDEX idx_investment_transactions_user_id ON investment_transactions(user_id);

-- Gmail connections table
CREATE TABLE gmail_connections (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    email VARCHAR2(255) NOT NULL,
    encrypted_refresh_token VARCHAR2(4000) NOT NULL,
    is_connected NUMBER(1) DEFAULT 1 NOT NULL,
    is_primary NUMBER(1) DEFAULT 0 NOT NULL,
    connected_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gmail_conn_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_gmail_user_email UNIQUE (user_id, email)
);

CREATE INDEX idx_gmail_connections_user ON gmail_connections(user_id);
CREATE INDEX idx_gmail_connections_email ON gmail_connections(email);

-- Gmail sync state table
CREATE TABLE gmail_sync_state (
    id VARCHAR2(36) PRIMARY KEY,
    connection_id VARCHAR2(36),
    user_id VARCHAR2(36),
    history_id VARCHAR2(255) NOT NULL,
    last_synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gmail_sync_conn FOREIGN KEY (connection_id) REFERENCES gmail_connections(id) ON DELETE CASCADE,
    CONSTRAINT fk_gmail_sync_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_gmail_sync_conn UNIQUE (connection_id)
);

-- Trigger for accounts updated_at
CREATE OR REPLACE TRIGGER trg_accounts_updated_at
BEFORE UPDATE ON accounts
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/

-- Trigger for gmail_connections updated_at
CREATE OR REPLACE TRIGGER trg_gmail_conn_updated_at
BEFORE UPDATE ON gmail_connections
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/

-- Trigger for gmail_sync_state updated_at
CREATE OR REPLACE TRIGGER trg_gmail_sync_updated_at
BEFORE UPDATE ON gmail_sync_state
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/
