-- 1. Recreate chat view v_chat_accounts without cc_payment_due_day
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

-- 2. Drop columns payment_due_day and grace_period_days from account_credit_card_details idempotently
DECLARE
    col_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO col_count FROM user_tab_columns WHERE table_name = 'ACCOUNT_CREDIT_CARD_DETAILS' AND column_name = 'PAYMENT_DUE_DAY';
    IF col_count > 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE account_credit_card_details DROP (payment_due_day, grace_period_days)';
    END IF;
END;
/
