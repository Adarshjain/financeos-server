-- Create Gmail senders allowlist table
CREATE TABLE gmail_senders (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36) NOT NULL,
    name VARCHAR2(255) NOT NULL,
    sender_address VARCHAR2(320) NOT NULL,
    account_id VARCHAR2(36) NULL,
    purpose VARCHAR2(20) DEFAULT 'TRANSACTION_ALERT' NOT NULL,
    attachment_pattern VARCHAR2(255) NULL,
    statement_format VARCHAR2(16) NULL,
    enabled NUMBER(1) DEFAULT 1 NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gmail_senders_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_gmail_senders_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL,
    CONSTRAINT chk_gmail_senders_purpose CHECK (purpose IN ('TRANSACTION_ALERT', 'STATEMENT')),
    CONSTRAINT chk_gmail_senders_enabled CHECK (enabled IN (0, 1))
);

CREATE INDEX idx_gmail_senders_user ON gmail_senders(user_id);
CREATE INDEX idx_gmail_senders_address ON gmail_senders(sender_address);

CREATE OR REPLACE TRIGGER trg_gmail_senders_updated_at
BEFORE UPDATE ON gmail_senders
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/

-- Update transactions source constraint to allow gmail_transaction_alert and gmail_statement
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_source;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_source
  CHECK (source IN ('gmail', 'gmail_transaction_alert', 'gmail_statement', 'manual'));
