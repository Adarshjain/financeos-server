-- Gmail OAuth and sync state tables

CREATE TABLE gmail_connections (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    encrypted_refresh_token TEXT NOT NULL,
    is_connected BOOLEAN NOT NULL DEFAULT TRUE,
    connected_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id)
);

CREATE INDEX idx_gmail_connections_user ON gmail_connections(user_id);
CREATE INDEX idx_gmail_connections_email ON gmail_connections(email);

CREATE TABLE gmail_sync_state (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    connection_id UUID REFERENCES gmail_connections(id) ON DELETE CASCADE,
    history_id TEXT NOT NULL,
    last_synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(connection_id)
);

CREATE INDEX idx_gmail_sync_state_connection ON gmail_sync_state(connection_id);

-- Function to update updated_at timestamp
CREATE TRIGGER update_gmail_connections_updated_at
    BEFORE UPDATE ON gmail_connections
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_gmail_sync_state_updated_at
    BEFORE UPDATE ON gmail_sync_state
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

