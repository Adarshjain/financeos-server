-- Flyway Migration V82: Credit Card Model v2 (accounts -> cardholders -> cards)

-- 1. Create cardholders table
CREATE TABLE cardholders (
    id            VARCHAR2(36) PRIMARY KEY,
    user_id       VARCHAR2(36) NOT NULL,
    account_id    VARCHAR2(36) NOT NULL,
    role          VARCHAR2(10) NOT NULL,
    person_name   VARCHAR2(200),
    relationship  VARCHAR2(20) NOT NULL,
    spend_limit   NUMBER(19,4),
    opened_on     DATE,
    closed_on     DATE,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_ch_user     FOREIGN KEY (user_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ch_account  FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT chk_ch_role    CHECK (role IN ('PRIMARY', 'ADDON')),
    CONSTRAINT chk_ch_rel     CHECK (relationship IN ('SELF', 'SPOUSE', 'PARENT', 'CHILD', 'SIBLING', 'OTHER')),
    CONSTRAINT chk_ch_limit   CHECK (spend_limit IS NULL OR spend_limit > 0),
    CONSTRAINT chk_ch_dates   CHECK (opened_on IS NULL OR closed_on IS NULL OR closed_on >= opened_on)
);

CREATE INDEX idx_ch_user    ON cardholders(user_id);
CREATE INDEX idx_ch_account ON cardholders(account_id);

-- Exactly one primary cardholder per account
CREATE UNIQUE INDEX uq_ch_primary ON cardholders (CASE WHEN role = 'PRIMARY' THEN account_id END);

-- 2. Create cards table
CREATE TABLE cards (
    id             VARCHAR2(36) PRIMARY KEY,
    user_id        VARCHAR2(36) NOT NULL,
    account_id     VARCHAR2(36) NOT NULL,
    cardholder_id  VARCHAR2(36) NOT NULL,
    last4          VARCHAR2(4)  NOT NULL,
    issued_on      DATE,
    closed_on      DATE,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_cards_user       FOREIGN KEY (user_id)       REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_cards_account    FOREIGN KEY (account_id)    REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_cards_cardholder FOREIGN KEY (cardholder_id) REFERENCES cardholders(id) ON DELETE CASCADE,
    CONSTRAINT chk_card_last4      CHECK (REGEXP_LIKE(last4, '^[0-9]{4}$')),
    CONSTRAINT chk_card_dates      CHECK (issued_on IS NULL OR closed_on IS NULL OR closed_on >= issued_on)
);

CREATE INDEX idx_cards_user       ON cards(user_id);
CREATE INDEX idx_cards_account    ON cards(account_id);
CREATE INDEX idx_cards_cardholder ON cards(cardholder_id);

-- One open card per cardholder, and last4 unique among open cards on an account
CREATE UNIQUE INDEX uq_card_open ON cards (CASE WHEN closed_on IS NULL THEN cardholder_id END);
CREATE UNIQUE INDEX uq_card_open_last4 ON cards (
    CASE WHEN closed_on IS NULL THEN account_id END,
    CASE WHEN closed_on IS NULL THEN last4      END);

-- 3. Add replaces_account_id to accounts for product upgrades
ALTER TABLE accounts ADD replaces_account_id VARCHAR2(36);
ALTER TABLE accounts ADD CONSTRAINT fk_acc_replaces FOREIGN KEY (replaces_account_id) REFERENCES accounts(id) ON DELETE SET NULL;
CREATE INDEX idx_acc_replaces ON accounts(replaces_account_id);

-- 4. Add issuer and product_name to account_credit_card_details
ALTER TABLE account_credit_card_details ADD (issuer VARCHAR2(100), product_name VARCHAR2(150));

-- 5. Seed cardholders & cards from existing data
-- Seed PRIMARY cardholders from primary account_cards
INSERT INTO cardholders (id, user_id, account_id, role, person_name, relationship, opened_on, closed_on, created_at, updated_at)
SELECT ac.id, ac.user_id, ac.account_id, 'PRIMARY', NULL, 'SELF', ac.opened_on, ac.closed_on, ac.created_at, ac.updated_at
FROM account_cards ac
WHERE ac.is_primary = 1;

-- Seed any credit_card accounts that don't have an account_cards row
INSERT INTO cardholders (id, user_id, account_id, role, person_name, relationship, opened_on, closed_on, created_at, updated_at)
SELECT a.id, a.user_id, a.id, 'PRIMARY', NULL, 'SELF', NULL, a.closed_on, a.created_at, a.updated_at
FROM accounts a
WHERE a.type = 'credit_card'
  AND NOT EXISTS (SELECT 1 FROM cardholders ch WHERE ch.account_id = a.id AND ch.role = 'PRIMARY');

-- Seed ADDON cardholders from add-on account_cards
INSERT INTO cardholders (id, user_id, account_id, role, person_name, relationship, opened_on, closed_on, created_at, updated_at)
SELECT ac.id, ac.user_id, ac.account_id, 'ADDON', ac.holder_name, NVL(ac.relationship, 'OTHER'), ac.opened_on, ac.closed_on, ac.created_at, ac.updated_at
FROM account_cards ac
WHERE ac.is_primary = 0;

-- Seed cards from card_instances
INSERT INTO cards (id, user_id, account_id, cardholder_id, last4, issued_on, closed_on, created_at, updated_at)
SELECT ci.id, ci.user_id, ci.account_id, ci.card_id, ci.last4, ci.issued_on, ci.closed_on, ci.created_at, ci.updated_at
FROM card_instances ci
JOIN cardholders ch ON ch.id = ci.card_id;

-- Seed default card for any cardholder that didn't have a card_instances row
INSERT INTO cards (id, user_id, account_id, cardholder_id, last4, issued_on, closed_on, created_at, updated_at)
SELECT LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
       ch.user_id, ch.account_id, ch.id, '0000', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM cardholders ch
WHERE NOT EXISTS (SELECT 1 FROM cards c WHERE c.cardholder_id = ch.id);

-- 6. Re-point foreign keys
-- Drop old foreign keys referencing account_cards
ALTER TABLE transactions DROP CONSTRAINT fk_txn_card;
ALTER TABLE reward_rules DROP CONSTRAINT fk_rr_card;
ALTER TABLE reward_milestones DROP CONSTRAINT fk_rm_card;
ALTER TABLE statements DROP CONSTRAINT fk_stmt_card;

-- Re-point transactions.card_id to cards(id)
-- Deterministic and date-aware: prefer the card whose validity range covers the transaction date,
-- then the open card, then the most recently issued. ROWNUM alone is scan-order roulette.
UPDATE transactions t
SET t.card_id = (
    SELECT c.id FROM cards c
    WHERE c.cardholder_id = t.card_id
    ORDER BY CASE WHEN (c.issued_on IS NULL OR c.issued_on <= t.transaction_date)
                   AND (c.closed_on IS NULL OR c.closed_on >= t.transaction_date)
                  THEN 0 ELSE 1 END,
             CASE WHEN c.closed_on IS NULL THEN 0 ELSE 1 END,
             c.issued_on DESC NULLS LAST
    FETCH FIRST 1 ROW ONLY)
WHERE t.card_id IS NOT NULL AND EXISTS (SELECT 1 FROM cardholders ch WHERE ch.id = t.card_id);

ALTER TABLE transactions ADD CONSTRAINT fk_txn_card FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE SET NULL;

-- reward_rules: drop card_id, add cardholder_id
ALTER TABLE reward_rules DROP COLUMN card_id;
ALTER TABLE reward_rules ADD cardholder_id VARCHAR2(36);
ALTER TABLE reward_rules ADD CONSTRAINT fk_rr_cardholder FOREIGN KEY (cardholder_id) REFERENCES cardholders(id) ON DELETE SET NULL;
CREATE INDEX idx_rr_cardholder ON reward_rules(cardholder_id);

-- reward_milestones: drop card_id, add cardholder_id
ALTER TABLE reward_milestones DROP COLUMN card_id;
ALTER TABLE reward_milestones ADD cardholder_id VARCHAR2(36);
ALTER TABLE reward_milestones ADD CONSTRAINT fk_rm_cardholder FOREIGN KEY (cardholder_id) REFERENCES cardholders(id) ON DELETE SET NULL;
CREATE INDEX idx_rm_cardholder ON reward_milestones(cardholder_id);

-- counter_scope: swap checks and update existing rows
ALTER TABLE reward_rules DROP CONSTRAINT chk_rr_counter_scope;
ALTER TABLE reward_cap_buckets DROP CONSTRAINT chk_rcb_counter_scope;

UPDATE reward_rules SET counter_scope = 'PER_CARDHOLDER' WHERE counter_scope = 'PER_CARD';
UPDATE reward_cap_buckets SET counter_scope = 'PER_CARDHOLDER' WHERE counter_scope = 'PER_CARD';

ALTER TABLE reward_rules ADD CONSTRAINT chk_rr_counter_scope CHECK (counter_scope IN ('ACCOUNT', 'PER_CARDHOLDER'));
ALTER TABLE reward_cap_buckets ADD CONSTRAINT chk_rcb_counter_scope CHECK (counter_scope IN ('ACCOUNT', 'PER_CARDHOLDER'));

-- statements: drop card_id
ALTER TABLE statements DROP COLUMN card_id;

-- 7. Drop old tables
DROP TABLE card_instances CASCADE CONSTRAINTS;
DROP TABLE account_cards CASCADE CONSTRAINTS;

-- 8. Recreate chat views and grant loop
CREATE OR REPLACE VIEW v_chat_accounts AS
SELECT
    a.id,
    a.name,
    a.type,
    a.exclude_from_net_asset,
    a.financial_position,
    a.description,
    a.closed_on,
    a.replaces_account_id,
    pc.last4 AS cc_last4,
    cc.credit_limit AS cc_credit_limit,
    cc.payment_due_day AS cc_payment_due_day,
    b.last4 AS bank_last4,
    b.opening_balance AS bank_opening_balance,
    br.cash_balance AS broker_cash_balance
FROM accounts a
LEFT JOIN account_credit_card_details cc ON cc.account_id = a.id
LEFT JOIN (
    SELECT ch.account_id, c.last4
    FROM cardholders ch
    JOIN cards c ON c.cardholder_id = ch.id
    WHERE ch.role = 'PRIMARY' AND ch.closed_on IS NULL AND c.closed_on IS NULL
) pc ON pc.account_id = a.id
LEFT JOIN account_bank_details b ON b.account_id = a.id
LEFT JOIN account_broker_details br ON br.account_id = a.id
WHERE a.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_cardholders AS
SELECT
    id,
    account_id,
    role,
    person_name,
    relationship,
    spend_limit,
    opened_on,
    closed_on
FROM cardholders
WHERE user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_cards AS
SELECT
    id,
    account_id,
    cardholder_id,
    last4,
    issued_on,
    closed_on
FROM cards
WHERE user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

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
    cat.category_names,
    a.id AS account_id,
    a.name AS account_name,
    a.type AS account_type,
    c.id AS card_id,
    c.last4 AS card_last4,
    ch.person_name AS card_holder,
    ch.relationship AS card_relationship
FROM transactions t
LEFT JOIN (
    SELECT tc.transaction_id,
           LISTAGG(c.name, ', ') WITHIN GROUP (ORDER BY c.name) AS category_names
    FROM transaction_categories tc
    JOIN categories c ON c.id = tc.category_id
    GROUP BY tc.transaction_id
) cat ON cat.transaction_id = t.id
LEFT JOIN accounts a ON a.id = t.account_id
LEFT JOIN cards c ON c.id = t.card_id
LEFT JOIN cardholders ch ON ch.id = c.cardholder_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- V80 created v_chat_account_cards and v_chat_card_instances over tables this migration drops.
-- They are now permanently INVALID, and GRANT on an invalid view raises ORA-04063, which the loop
-- below re-raises — this is exactly where the first run of this migration died. Drop them first.
-- (Defensive: ignore ORA-00942 so a future compressed baseline that never created them still passes.)
DECLARE
  PROCEDURE drop_view_if_present(v VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE 'DROP VIEW ' || v;
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE != -942 THEN RAISE; END IF;
  END;
BEGIN
  drop_view_if_present('v_chat_account_cards');
  drop_view_if_present('v_chat_card_instances');
END;
/

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
