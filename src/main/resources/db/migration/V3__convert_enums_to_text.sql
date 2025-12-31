-- Convert PostgreSQL native enums to TEXT for better Hibernate compatibility
-- Application layer will handle validation

-- Accounts table
ALTER TABLE accounts 
    ALTER COLUMN type TYPE TEXT,
    ALTER COLUMN financial_position TYPE TEXT;

-- Transactions table
ALTER TABLE transactions
    ALTER COLUMN source TYPE TEXT;

-- Investment transactions table
ALTER TABLE investment_transactions
    ALTER COLUMN type TYPE TEXT;

-- Drop the enum types (they're no longer needed)
DROP TYPE IF EXISTS account_type CASCADE;
DROP TYPE IF EXISTS financial_position CASCADE;
DROP TYPE IF EXISTS transaction_source CASCADE;
DROP TYPE IF EXISTS investment_transaction_type CASCADE;

