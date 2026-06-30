ALTER TABLE gmail_senders ADD account_id VARCHAR2(36) NULL;
ALTER TABLE gmail_senders ADD CONSTRAINT fk_gmail_senders_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL;
