-- V61__add_account_status.sql
ALTER TABLE accounts ADD status VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL;
ALTER TABLE accounts ADD closed_on DATE;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE','CLOSED'));
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_closed_on CHECK (
    (status = 'ACTIVE' AND closed_on IS NULL) OR (status = 'CLOSED' AND closed_on IS NOT NULL));
CREATE INDEX idx_accounts_status ON accounts(status);
