-- Add Google SSO fields to users table
ALTER TABLE users ADD (
    google_id VARCHAR2(255),
    display_name VARCHAR2(500),
    picture_url VARCHAR2(1000)
);

-- Add unique constraint
ALTER TABLE users ADD CONSTRAINT uq_users_google_id UNIQUE (google_id);