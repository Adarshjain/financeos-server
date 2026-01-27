-- Allow multiple Gmail connections per user
ALTER TABLE gmail_connections DROP CONSTRAINT uq_gmail_conn_user;

-- Add is_primary flag to identify SSO-connected account
ALTER TABLE gmail_connections ADD is_primary NUMBER(1) DEFAULT 0 NOT NULL;
ALTER TABLE gmail_connections ADD CONSTRAINT chk_gmail_primary CHECK (is_primary IN (0, 1));

-- Create unique constraint for email per user (can't connect same Gmail twice)
ALTER TABLE gmail_connections ADD CONSTRAINT uq_gmail_user_email UNIQUE(user_id, email);
