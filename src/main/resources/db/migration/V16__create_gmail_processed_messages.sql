-- Create processed message ledger table for Gmail Ingestion
CREATE TABLE gmail_processed_messages (
    id VARCHAR2(36) PRIMARY KEY,
    connection_id VARCHAR2(36) NOT NULL,
    user_id VARCHAR2(36) NOT NULL,
    gmail_message_id VARCHAR2(255) NOT NULL,
    status VARCHAR2(40) NOT NULL,
    transaction_id VARCHAR2(36) NULL,
    error VARCHAR2(2000) NULL,
    processed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_gmail_proc_conn FOREIGN KEY (connection_id) REFERENCES gmail_connections(id) ON DELETE CASCADE,
    CONSTRAINT fk_gmail_proc_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_gmail_proc_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT uk_gmail_proc UNIQUE (connection_id, gmail_message_id)
);
