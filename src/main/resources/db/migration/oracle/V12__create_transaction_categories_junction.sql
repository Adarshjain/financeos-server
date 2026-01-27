-- Create transaction_categories junction table for many-to-many relationship
CREATE TABLE transaction_categories (
    transaction_id RAW(16) NOT NULL,
    category_id RAW(16) NOT NULL,
    CONSTRAINT pk_transaction_categories PRIMARY KEY (transaction_id, category_id),
    CONSTRAINT fk_trans_cat_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_trans_cat_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
);

-- Create indexes on foreign keys for efficient joins
CREATE INDEX idx_trans_cat_trans_id ON transaction_categories(transaction_id);
CREATE INDEX idx_trans_cat_cat_id ON transaction_categories(category_id);

-- Remove old category columns from transactions table
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP COLUMN category';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -904 THEN -- ORA-00904: invalid identifier
            RAISE;
        END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP COLUMN subcategory';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -904 THEN
            RAISE;
        END IF;
END;
/

-- Drop old category index if it exists
BEGIN
    EXECUTE IMMEDIATE 'DROP INDEX idx_transactions_category';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -1418 THEN -- ORA-01418: specified index does not exist
            RAISE;
        END IF;
END;
/
