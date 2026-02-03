-- Seed initial user for personal use
-- Password: changeme (BCrypt hash with cost factor 12)
-- IMPORTANT: Change this password immediately after first login

INSERT INTO users (id, email, password_hash, created_at)
VALUES (
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    'admin@financeos.local',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.5XLPmS8QAVKf0u',
    CURRENT_TIMESTAMP
)
ON CONFLICT (email) DO NOTHING;

