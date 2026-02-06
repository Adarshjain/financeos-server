-- Drop metadata column from transactions table
ALTER TABLE transactions DROP COLUMN metadata;

-- Drop metadata column from investment_transactions table
ALTER TABLE investment_transactions DROP COLUMN metadata;
