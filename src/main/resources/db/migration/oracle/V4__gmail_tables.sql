-- Gmail OAuth and sync state tables

CREATE TABLE gmail_connections (
    id RAW(16) PRIMARY KEY,
    user_id RAW(16),
    email VARCHAR2(255) NOT NULL,
    encrypted_refresh_token VARCHAR2(1000) NOT NULL,
    is_connected NUMBER(1) DEFAULT 1 NOT NULL,
    connected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gmail_conn_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_gmail_conn_user UNIQUE(user_id),
    CONSTRAINT chk_gmail_connected CHECK (is_connected IN (0, 1))
);

CREATE INDEX idx_gmail_connections_email ON gmail_connections(email);

CREATE TABLE gmail_sync_state (
    id RAW(16) PRIMARY KEY,
    connection_id RAW(16),
    history_id VARCHAR2(255) NOT NULL,
    last_synced_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gmail_sync_conn FOREIGN KEY (connection_id) REFERENCES gmail_connections(id) ON DELETE CASCADE,
    CONSTRAINT uq_gmail_sync_conn UNIQUE(connection_id)
);

-- Trigger for gmail_connections updated_at
CREATE OR REPLACE TRIGGER trg_gmail_conn_updated_at
BEFORE UPDATE ON gmail_connections
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/

-- Trigger for gmail_sync_state updated_at
CREATE OR REPLACE TRIGGER trg_gmail_sync_updated_at
BEFORE UPDATE ON gmail_sync_state
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/
