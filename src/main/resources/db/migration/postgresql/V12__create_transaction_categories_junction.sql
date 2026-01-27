-- Create transaction_categories junction table for many-to-many relationship
CREATE TABLE transaction_categories (
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (transaction_id, category_id)
);

-- Create indexes on foreign keys for efficient joins
CREATE INDEX idx_transaction_categories_transaction_id ON transaction_categories(transaction_id);
CREATE INDEX idx_transaction_categories_category_id ON transaction_categories(category_id);

-- Remove old category columns from transactions table
ALTER TABLE transactions DROP COLUMN IF EXISTS category;
ALTER TABLE transactions DROP COLUMN IF EXISTS subcategory;

-- Drop old category index if it exists
DROP INDEX IF EXISTS idx_transactions_category;
