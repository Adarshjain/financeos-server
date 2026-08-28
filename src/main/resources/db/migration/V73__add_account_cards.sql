-- Flyway Migration V73: Add Account Cards (Add-on/Supplementary Cards) & Transaction Attribution

CREATE TABLE account_cards (
    id           VARCHAR2(36) PRIMARY KEY,
    user_id      VARCHAR2(36) NOT NULL,
    account_id   VARCHAR2(36) NOT NULL,
    label        VARCHAR2(100),
    holder_name  VARCHAR2(200),
    relationship VARCHAR2(20) NOT NULL,
    last4        VARCHAR2(4)  NOT NULL,
    is_primary   NUMBER(1)    DEFAULT 0 NOT NULL,
    issued_on    DATE,
    closed_on    DATE,
    spend_limit  NUMBER(19,4),
    note         VARCHAR2(500),
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_ac_user     FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT fk_ac_account  FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT chk_ac_rel     CHECK (relationship IN ('SELF','SPOUSE','PARENT','CHILD','SIBLING','OTHER')),
    CONSTRAINT chk_ac_last4   CHECK (REGEXP_LIKE(last4, '^[0-9]{4}$')),
    CONSTRAINT chk_ac_primary CHECK (is_primary IN (0,1)),
    CONSTRAINT chk_ac_limit   CHECK (spend_limit IS NULL OR spend_limit > 0),
    CONSTRAINT chk_ac_dates   CHECK (issued_on IS NULL OR closed_on IS NULL OR closed_on >= issued_on)
);

CREATE INDEX idx_ac_user    ON account_cards(user_id);
CREATE INDEX idx_ac_account ON account_cards(account_id);
CREATE INDEX idx_ac_last4   ON account_cards(user_id, last4);

-- At most ONE open primary per account. Oracle skips all-null index entries.
CREATE UNIQUE INDEX uq_ac_primary ON account_cards (
    CASE WHEN is_primary = 1 AND closed_on IS NULL THEN account_id END);

-- last4 unique among OPEN cards on an account.
CREATE UNIQUE INDEX uq_ac_open_last4 ON account_cards (
    CASE WHEN closed_on IS NULL THEN account_id END,
    CASE WHEN closed_on IS NULL THEN last4      END);

-- Backfill primary card for each existing credit card account.
INSERT INTO account_cards (id, user_id, account_id, label, relationship, last4, is_primary,
                           issued_on, created_at, updated_at)
SELECT LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()),
           '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
       a.user_id, a.id, 'Primary', 'SELF', cc.last4, 1,
       a.reward_anniversary_date, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM accounts a
JOIN account_credit_card_details cc ON cc.account_id = a.id
WHERE a.type = 'credit_card';

-- Add card_id to transactions with ON DELETE SET NULL
ALTER TABLE transactions ADD card_id VARCHAR2(36);
ALTER TABLE transactions ADD CONSTRAINT fk_txn_card
    FOREIGN KEY (card_id) REFERENCES account_cards(id) ON DELETE SET NULL;
CREATE INDEX idx_txn_card ON transactions(card_id);

-- Backfill transactions.card_id to primary card
UPDATE transactions t
SET t.card_id = (SELECT c.id FROM account_cards c
                 WHERE c.account_id = t.account_id AND c.is_primary = 1)
WHERE EXISTS (SELECT 1 FROM account_cards c WHERE c.account_id = t.account_id);

-- Add card_id to statements with ON DELETE SET NULL
ALTER TABLE statements ADD card_id VARCHAR2(36);
ALTER TABLE statements ADD CONSTRAINT fk_stmt_card
    FOREIGN KEY (card_id) REFERENCES account_cards(id) ON DELETE SET NULL;
CREATE INDEX idx_stmt_card ON statements(card_id);

-- Recreate chat views before dropping column from account_credit_card_details
CREATE OR REPLACE VIEW v_chat_transactions AS
SELECT
    t.id,
    t.transaction_date,
    t.settlement_date,
    t.amount,
    t.type AS direction,
    t.description,
    t.sourced_description,
    t.mcc,
    t.channel,
    t.is_emi,
    t.is_international,
    t.is_under_monitoring,
    t.review_type,
    t.is_excluded,
    t.instant_discount,
    t.convenience_fee,
    t.card_id,
    ac.label AS card_label,
    ac.holder_name AS card_holder,
    cat.category_names,
    a.id AS account_id,
    a.name AS account_name,
    a.type AS account_type
FROM transactions t
LEFT JOIN account_cards ac ON ac.id = t.card_id
LEFT JOIN (
    SELECT tc.transaction_id,
           LISTAGG(c.name, ', ') WITHIN GROUP (ORDER BY c.name) AS category_names
    FROM transaction_categories tc
    JOIN categories c ON c.id = tc.category_id
    GROUP BY tc.transaction_id
) cat ON cat.transaction_id = t.id
LEFT JOIN accounts a ON a.id = t.account_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

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
LEFT JOIN account_cards pc ON pc.account_id = a.id AND pc.is_primary = 1 AND pc.closed_on IS NULL
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
    last4,
    is_primary,
    issued_on,
    closed_on
FROM account_cards
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

-- Drop last4 from account_credit_card_details (single source of truth in account_cards)
ALTER TABLE account_credit_card_details DROP COLUMN last4;
