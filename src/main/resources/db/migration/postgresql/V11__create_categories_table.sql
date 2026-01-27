-- Create categories table
CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT categories_user_name_unique UNIQUE (user_id, name)
);

-- Create index on user_id for filtering
CREATE INDEX idx_categories_user_id ON categories(user_id);

-- Create index on name for search queries
CREATE INDEX idx_categories_name ON categories(name);
