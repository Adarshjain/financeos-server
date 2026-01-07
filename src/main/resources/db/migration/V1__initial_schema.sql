-- FinanceOS Initial Schema
-- V1: Core tables for personal finance tracking

/*
  INITIAL SETUP (Run as 'postgres' superuser)
  -------------------------------------------
  CREATE DATABASE financeos;
  CREATE USER financeos WITH PASSWORD 'financeos';
  GRANT ALL PRIVILEGES ON DATABASE financeos TO financeos;
  
  -- Connect to the new database before running the rest of the script
  \c financeos
*/

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Account type enum
CREATE TYPE account_type AS ENUM (
    'bank_account',
    'credit_card',
    'stock',
    'mutual_fund',
    'generic'
);

-- Financial position enum
CREATE TYPE financial_position AS ENUM (
    'asset',
    'liability'
);

-- Transaction source enum
CREATE TYPE transaction_source AS ENUM (
    'gmail',
    'manual'
);

-- Investment transaction type enum
CREATE TYPE investment_transaction_type AS ENUM (
    'buy',
    'sell'
);

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);

-- Accounts table
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    type account_type NOT NULL,
    exclude_from_net_asset BOOLEAN NOT NULL DEFAULT FALSE,
    financial_position financial_position,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_accounts_type ON accounts(type);
CREATE INDEX idx_accounts_financial_position ON accounts(financial_position);

-- Bank account details table (1:1 with accounts)
CREATE TABLE account_bank_details (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    opening_balance NUMERIC(19, 4),
    last4 TEXT
);

-- Credit card details table (1:1 with accounts)
CREATE TABLE account_credit_card_details (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    last4 TEXT NOT NULL,
    credit_limit NUMERIC(19, 4) NOT NULL,
    payment_due_day INTEGER NOT NULL CHECK (payment_due_day BETWEEN 1 AND 31),
    grace_period_days INTEGER NOT NULL CHECK (grace_period_days >= 0),
    statement_password TEXT  -- Encrypted field
);

-- Stock details table (1:1 with accounts)
CREATE TABLE account_stock_details (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    instrument_code TEXT NOT NULL,
    last_traded_price NUMERIC(19, 4)
);

CREATE INDEX idx_stock_details_instrument ON account_stock_details(instrument_code);

-- Mutual fund details table (1:1 with accounts)
CREATE TABLE account_mutual_fund_details (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    instrument_code TEXT NOT NULL,
    last_traded_price NUMERIC(19, 4)
);

CREATE INDEX idx_mutual_fund_details_instrument ON account_mutual_fund_details(instrument_code);

-- Transactions table
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    date DATE NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    description TEXT NOT NULL,
    category TEXT,
    subcategory TEXT,
    spent_for TEXT,
    source transaction_source NOT NULL,
    original_hash TEXT UNIQUE NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transactions_account ON transactions(account_id);
CREATE INDEX idx_transactions_date ON transactions(date);
CREATE INDEX idx_transactions_category ON transactions(category);
CREATE INDEX idx_transactions_source ON transactions(source);

-- Investment transactions table
CREATE TABLE investment_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    type investment_transaction_type NOT NULL,
    quantity NUMERIC(19, 8) NOT NULL CHECK (quantity > 0),
    price NUMERIC(19, 4) NOT NULL CHECK (price >= 0),
    date DATE NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_investment_transactions_account ON investment_transactions(account_id);
CREATE INDEX idx_investment_transactions_date ON investment_transactions(date);
CREATE INDEX idx_investment_transactions_type ON investment_transactions(type);

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Trigger for accounts updated_at
CREATE TRIGGER update_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

