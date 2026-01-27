-- FinanceOS Initial Schema for Oracle
-- V1: Core tables for personal finance tracking with UUID7 support

-- Users table
CREATE TABLE users (
    id RAW(16) PRIMARY KEY,
    email VARCHAR2(255) UNIQUE NOT NULL,
    password_hash VARCHAR2(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Accounts table
CREATE TABLE accounts (
    id RAW(16) PRIMARY KEY,
    name VARCHAR2(500) NOT NULL,
    type VARCHAR2(50) NOT NULL,
    exclude_from_net_asset NUMBER(1) DEFAULT 0 NOT NULL,
    financial_position VARCHAR2(50),
    description CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_accounts_exclude CHECK (exclude_from_net_asset IN (0, 1))
);

CREATE INDEX idx_accounts_type ON accounts(type);
CREATE INDEX idx_accounts_financial_position ON accounts(financial_position);

-- Bank account details table (1:1 with accounts)
CREATE TABLE account_bank_details (
    account_id RAW(16) PRIMARY KEY,
    opening_balance NUMBER(19, 4),
    last4 VARCHAR2(4),
    CONSTRAINT fk_bank_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

-- Credit card details table (1:1 with accounts)
CREATE TABLE account_credit_card_details (
    account_id RAW(16) PRIMARY KEY,
    last4 VARCHAR2(4) NOT NULL,
    credit_limit NUMBER(19, 4) NOT NULL,
    payment_due_day NUMBER(2) NOT NULL,
    grace_period_days NUMBER(3) NOT NULL,
    statement_password VARCHAR2(500),
    CONSTRAINT fk_cc_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT chk_cc_due_day CHECK (payment_due_day BETWEEN 1 AND 31),
    CONSTRAINT chk_cc_grace CHECK (grace_period_days >= 0)
);

-- Stock details table (1:1 with accounts)
CREATE TABLE account_stock_details (
    account_id RAW(16) PRIMARY KEY,
    instrument_code VARCHAR2(100) NOT NULL,
    last_traded_price NUMBER(19, 4),
    CONSTRAINT fk_stock_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_stock_details_instrument ON account_stock_details(instrument_code);

-- Mutual fund details table (1:1 with accounts)
CREATE TABLE account_mutual_fund_details (
    account_id RAW(16) PRIMARY KEY,
    instrument_code VARCHAR2(100) NOT NULL,
    last_traded_price NUMBER(19, 4),
    CONSTRAINT fk_mf_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_mutual_fund_details_instrument ON account_mutual_fund_details(instrument_code);

-- Transactions table
CREATE TABLE transactions (
    id RAW(16) PRIMARY KEY,
    account_id RAW(16),
    transaction_date DATE NOT NULL,
    amount NUMBER(19, 4) NOT NULL,
    description VARCHAR2(1000) NOT NULL,
    category VARCHAR2(255),
    subcategory VARCHAR2(255),
    spent_for VARCHAR2(255),
    source VARCHAR2(50) NOT NULL,
    original_hash VARCHAR2(255) UNIQUE NOT NULL,
    metadata CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trans_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL
);

CREATE INDEX idx_transactions_account ON transactions(account_id);
CREATE INDEX idx_transactions_date ON transactions(transaction_date);
CREATE INDEX idx_transactions_category ON transactions(category);
CREATE INDEX idx_transactions_source ON transactions(source);

-- Investment transactions table
CREATE TABLE investment_transactions (
    id RAW(16) PRIMARY KEY,
    account_id RAW(16),
    type VARCHAR2(50) NOT NULL,
    quantity NUMBER(19, 8) NOT NULL,
    price NUMBER(19, 4) NOT NULL,
    transaction_date DATE NOT NULL,
    metadata CLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inv_trans_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL,
    CONSTRAINT chk_inv_quantity CHECK (quantity > 0),
    CONSTRAINT chk_inv_price CHECK (price >= 0)
);

CREATE INDEX idx_investment_transactions_account ON investment_transactions(account_id);
CREATE INDEX idx_investment_transactions_date ON investment_transactions(transaction_date);
CREATE INDEX idx_investment_transactions_type ON investment_transactions(type);

-- Trigger for accounts updated_at
CREATE OR REPLACE TRIGGER trg_accounts_updated_at
BEFORE UPDATE ON accounts
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/
