-- Flyway Migration V81: Add Account Closure (closed_on)

-- Schema drift normalisation. Some environments carry `accounts.status` plus chk_accounts_status /
-- chk_accounts_closed_on from an abandoned card-fees experiment; no migration in this repo ever created
-- them. chk_accounts_closed_on demands status='CLOSED' whenever closed_on IS NOT NULL, which would reject
-- every closure this migration enables (ORA-02290). The plan deliberately rejects a status column -- one
-- nullable date is the single source of truth -- so drop the orphans wherever they exist.
DECLARE
  PROCEDURE drop_if_present(sql_text VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE sql_text;
  EXCEPTION WHEN OTHERS THEN
    -- 2443: constraint does not exist. 904: column does not exist. Anything else is real.
    IF SQLCODE NOT IN (-2443, -904) THEN RAISE; END IF;
  END;
BEGIN
  drop_if_present('ALTER TABLE accounts DROP CONSTRAINT chk_accounts_closed_on');
  drop_if_present('ALTER TABLE accounts DROP CONSTRAINT chk_accounts_status');
  drop_if_present('ALTER TABLE accounts DROP COLUMN status');
END;
/

ALTER TABLE accounts ADD closed_on DATE;
CREATE INDEX idx_accounts_closed_on ON accounts(closed_on);

-- Recreate v_chat_accounts with closed_on
CREATE OR REPLACE VIEW v_chat_accounts AS
SELECT
    a.id,
    a.name,
    a.type,
    a.exclude_from_net_asset,
    a.financial_position,
    a.description,
    a.closed_on,
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
