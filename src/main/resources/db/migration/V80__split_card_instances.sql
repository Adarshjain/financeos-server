-- Flyway Migration V80: Split Card Instances (Plastics) from Account Cards (Card Lines)

CREATE TABLE card_instances (
    id                   VARCHAR2(36) PRIMARY KEY,
    user_id              VARCHAR2(36) NOT NULL,
    account_id           VARCHAR2(36) NOT NULL,
    card_id              VARCHAR2(36) NOT NULL,
    last4                VARCHAR2(4)  NOT NULL,
    issued_on            DATE,
    closed_on            DATE,
    replaces_instance_id VARCHAR2(36),
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_ci_user     FOREIGN KEY (user_id)              REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ci_account  FOREIGN KEY (account_id)           REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_ci_card     FOREIGN KEY (card_id)              REFERENCES account_cards(id) ON DELETE CASCADE,
    CONSTRAINT fk_ci_replaces FOREIGN KEY (replaces_instance_id) REFERENCES card_instances(id) ON DELETE SET NULL,
    CONSTRAINT chk_ci_last4   CHECK (REGEXP_LIKE(last4, '^[0-9]{4}$')),
    CONSTRAINT chk_ci_dates   CHECK (issued_on IS NULL OR closed_on IS NULL OR closed_on >= issued_on)
);

CREATE INDEX idx_ci_user    ON card_instances(user_id);
CREATE INDEX idx_ci_account ON card_instances(account_id);
CREATE INDEX idx_ci_card    ON card_instances(card_id);
CREATE INDEX idx_ci_last4   ON card_instances(user_id, last4);

-- Backfill one instance per existing line, inheriting its number and dates verbatim
INSERT INTO card_instances (id, user_id, account_id, card_id, last4, issued_on, closed_on, created_at, updated_at)
SELECT LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()),
           '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
       c.user_id, c.account_id, c.id, c.last4, c.issued_on, c.closed_on, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM account_cards c;

-- Add opened_on to account_cards and copy issued_on before dropping it
ALTER TABLE account_cards ADD opened_on DATE;
UPDATE account_cards SET opened_on = issued_on;

-- Drop old unique last4 index on account_cards and create scoped unique index on card_instances
DROP INDEX uq_ac_open_last4;

CREATE UNIQUE INDEX uq_ci_open_last4 ON card_instances (
    CASE WHEN closed_on IS NULL THEN account_id END,
    CASE WHEN closed_on IS NULL THEN last4      END);

-- Drop plastic attributes from account_cards line table.
-- chk_ac_dates spans issued_on AND closed_on, so Oracle raises ORA-12991 ("column is referenced in a
-- multi-column constraint") if the column is dropped while it stands. Drop the dependent checks first,
-- then re-state the date invariant against the line's own opened_on.
ALTER TABLE account_cards DROP CONSTRAINT chk_ac_dates;
ALTER TABLE account_cards DROP CONSTRAINT chk_ac_last4;

ALTER TABLE account_cards DROP (last4, issued_on);

ALTER TABLE account_cards ADD CONSTRAINT chk_ac_dates
    CHECK (opened_on IS NULL OR closed_on IS NULL OR closed_on >= opened_on);

-- Recreate chat views and grant read-only access
CREATE OR REPLACE VIEW v_chat_accounts AS
SELECT
    a.id,
    a.name,
    a.type,
    a.exclude_from_net_asset,
    a.financial_position,
    a.description,
    pc.last4 AS cc_last4,
    cc.credit_limit AS cc_credit_limit,
    cc.payment_due_day AS cc_payment_due_day,
    b.last4 AS bank_last4,
    b.opening_balance AS bank_opening_balance,
    br.cash_balance AS broker_cash_balance
FROM accounts a
LEFT JOIN account_credit_card_details cc ON cc.account_id = a.id
LEFT JOIN (
    SELECT ac.account_id, ci.last4
    FROM account_cards ac
    JOIN card_instances ci ON ci.card_id = ac.id
    WHERE ac.is_primary = 1 AND ac.closed_on IS NULL AND ci.closed_on IS NULL
) pc ON pc.account_id = a.id
LEFT JOIN account_bank_details b ON b.account_id = a.id
LEFT JOIN account_broker_details br ON br.account_id = a.id
WHERE a.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_account_cards AS
SELECT
    id,
    account_id,
    label,
    holder_name,
    relationship,
    is_primary,
    opened_on,
    closed_on
FROM account_cards
WHERE user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_card_instances AS
SELECT
    id,
    account_id,
    card_id,
    last4,
    issued_on,
    closed_on,
    replaces_instance_id
FROM card_instances
WHERE user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

BEGIN
  FOR v IN (SELECT view_name FROM user_views WHERE view_name LIKE 'V_CHAT_%') LOOP
    BEGIN
      EXECUTE IMMEDIATE 'GRANT SELECT ON ' || v.view_name || ' TO chat_ro';
    EXCEPTION WHEN OTHERS THEN
      IF SQLCODE != -1917 THEN RAISE; END IF;
    END;
  END LOOP;
END;
/
