ALTER SESSION DISABLE PARALLEL DML;

-- Create gmail_sync_cursors table
DECLARE
    tbl_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO tbl_count FROM user_tables WHERE table_name = 'GMAIL_SYNC_CURSORS';
    IF tbl_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE gmail_sync_cursors (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            connection_id VARCHAR2(36) NOT NULL,
            sender_id VARCHAR2(36) NOT NULL,
            last_listed_at TIMESTAMP NOT NULL,
            earliest_covered_at TIMESTAMP NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT fk_gsc_user FOREIGN KEY (user_id) REFERENCES users(id),
            CONSTRAINT fk_gsc_conn FOREIGN KEY (connection_id) REFERENCES gmail_connections(id) ON DELETE CASCADE,
            CONSTRAINT fk_gsc_sender FOREIGN KEY (sender_id) REFERENCES gmail_senders(id) ON DELETE CASCADE,
            CONSTRAINT uk_gsc_conn_sender UNIQUE (connection_id, sender_id)
        )';
    END IF;
END;
/

-- Seed cursors for every connected connection x enabled sender
INSERT INTO gmail_sync_cursors (id, user_id, connection_id, sender_id, last_listed_at, earliest_covered_at, created_at, updated_at)
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
    c.user_id,
    c.id,
    s.id,
    COALESCE(gss.last_synced_at, c.created_at, CURRENT_TIMESTAMP),
    CASE 
        WHEN s.created_at > (c.created_at - INTERVAL '30' DAY) THEN s.created_at 
        ELSE (c.created_at - INTERVAL '30' DAY) 
    END,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM gmail_connections c
JOIN gmail_senders s ON s.user_id = c.user_id AND s.enabled = 1
LEFT JOIN gmail_sync_state gss ON gss.connection_id = c.id
WHERE c.is_connected = 1
  AND NOT EXISTS (
      SELECT 1 FROM gmail_sync_cursors gsc WHERE gsc.connection_id = c.id AND gsc.sender_id = s.id
  );
