-- Create categories table
CREATE TABLE categories (
    id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
    user_id RAW(16) NOT NULL,
    name VARCHAR2(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT categories_user_name_unique UNIQUE (user_id, name)
);

-- Create index on user_id for filtering
CREATE INDEX idx_categories_user_id ON categories(user_id);

-- Create index on name for search queries
CREATE INDEX idx_categories_name ON categories(name);
