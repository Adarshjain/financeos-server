-- Seed initial user for personal use
-- Password: changeme (BCrypt hash with cost factor 12)
-- IMPORTANT: Change this password immediately after first login

MERGE INTO users u
USING (
    SELECT 
        HEXTORAW(REPLACE('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '-', '')) AS id,
        'admin@financeos.local' AS email,
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4.5XLPmS8QAVKf0u' AS password_hash,
        CURRENT_TIMESTAMP AS created_at
    FROM dual
) src
ON (u.email = src.email)
WHEN NOT MATCHED THEN
    INSERT (id, email, password_hash, created_at)
    VALUES (src.id, src.email, src.password_hash, src.created_at);
