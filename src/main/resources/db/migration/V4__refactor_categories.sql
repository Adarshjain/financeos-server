-- Create categories table
CREATE TABLE categories (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    name VARCHAR2(255) NOT NULL,
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_categories_user_id ON categories(user_id);
CREATE INDEX idx_categories_name ON categories(name);

-- Create many-to-many join table
CREATE TABLE transaction_categories (
    transaction_id VARCHAR2(36) NOT NULL,
    category_id VARCHAR2(36) NOT NULL,
    PRIMARY KEY (transaction_id, category_id),
    CONSTRAINT fk_tc_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_tc_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
);

CREATE INDEX idx_tc_transaction_id ON transaction_categories(transaction_id);
CREATE INDEX idx_tc_category_id ON transaction_categories(category_id);

-- Remove old columns from transactions
-- Note: Existing data in these columns will be lost
ALTER TABLE transactions DROP COLUMN category;
ALTER TABLE transactions DROP COLUMN subcategory;
ALTER TABLE transactions DROP COLUMN spent_for;
