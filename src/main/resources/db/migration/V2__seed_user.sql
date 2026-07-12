-- Seed initial user for personal use
-- Password: changeme (BCrypt hash with cost factor 12)
-- IMPORTANT: Change this password immediately after first login

MERGE INTO users u
USING (
    SELECT 
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11' AS id,
        'admin@financeos.local' AS email,
        '$2a$12$hk7CgIyMBG919vNSYU4saerqD7/lGIbWspcYgsfq7vSqLvzjh0MRm' AS password_hash,
        CURRENT_TIMESTAMP AS created_at
    FROM dual
) src
ON (u.email = src.email)
WHEN NOT MATCHED THEN
    INSERT (id, email, password_hash, created_at)
    VALUES (src.id, src.email, src.password_hash, src.created_at);

COMMIT;
