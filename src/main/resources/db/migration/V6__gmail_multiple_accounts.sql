-- Allow multiple Gmail connections per user
ALTER TABLE gmail_connections DROP CONSTRAINT gmail_connections_user_id_key;

-- Add is_primary flag to identify SSO-connected account
ALTER TABLE gmail_connections ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT FALSE;

-- Create unique constraint for email per user (can't connect same Gmail twice)
ALTER TABLE gmail_connections ADD CONSTRAINT gmail_connections_user_email_unique UNIQUE(user_id, email);
