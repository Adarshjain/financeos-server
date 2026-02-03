-- Add Google SSO fields to users table
ALTER TABLE users ADD COLUMN google_id TEXT UNIQUE;
ALTER TABLE users ADD COLUMN display_name TEXT;
ALTER TABLE users ADD COLUMN picture_url TEXT;

-- Make password_hash nullable for SSO-only users
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE INDEX idx_users_google_id ON users(google_id);
