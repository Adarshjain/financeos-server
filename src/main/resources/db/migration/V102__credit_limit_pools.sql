-- V102__credit_limit_pools.sql
-- Shared credit limits: some banks give several credit card accounts one limit between them.
-- A pool holds that shared limit; a credit card account joins at most one pool. A pooled account's
-- own credit_limit becomes optional (null = it can use the whole pool), so the NOT NULL goes; the
-- service still requires a limit on every account that is not in a pool.
--
-- Idempotent: every DDL swallows its "already done" error, and the index is guarded on a LEADING
-- column, not its name, since Oracle DDL auto-commits and a half-applied run is the normal failure.

-- 1. The pool table
BEGIN
    EXECUTE IMMEDIATE q'[
        CREATE TABLE credit_limit_pools (
            id            VARCHAR2(36) PRIMARY KEY,
            user_id       VARCHAR2(36) NOT NULL,
            name          VARCHAR2(150) NOT NULL,
            credit_limit  NUMBER(19,4) NOT NULL,
            created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
            updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT fk_clp_user  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT chk_clp_limit CHECK (credit_limit > 0)
        )]';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -955 THEN
            RAISE;
        END IF;
END;
/

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_ind_columns
     WHERE table_name = 'CREDIT_LIMIT_POOLS' AND column_name = 'USER_ID' AND column_position = 1;
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_clp_user ON credit_limit_pools(user_id)';
    END IF;
END;
/

-- 2. Pool membership lives on the credit card details row: only credit cards can join a pool.
--    ON DELETE SET NULL (not CASCADE, not NO ACTION) so deleting a user, which cascades into both
--    this table and credit_limit_pools in one statement, never trips over the row order.
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE account_credit_card_details ADD limit_pool_id VARCHAR2(36)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -1430 THEN
            RAISE;
        END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE account_credit_card_details ADD CONSTRAINT fk_accd_limit_pool '
        || 'FOREIGN KEY (limit_pool_id) REFERENCES credit_limit_pools(id) ON DELETE SET NULL';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE NOT IN (-2275, -2264) THEN
            RAISE;
        END IF;
END;
/

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_ind_columns
     WHERE table_name = 'ACCOUNT_CREDIT_CARD_DETAILS' AND column_name = 'LIMIT_POOL_ID' AND column_position = 1;
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_accd_limit_pool ON account_credit_card_details(limit_pool_id)';
    END IF;
END;
/

-- 3. A pooled account's own limit is optional.
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE account_credit_card_details MODIFY credit_limit NULL';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -1451 THEN
            RAISE;
        END IF;
END;
/

-- 4. Chat: expose the shared limit next to the account's own.
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
    clp.id AS cc_limit_pool_id,
    clp.name AS cc_limit_pool_name,
    clp.credit_limit AS cc_limit_pool_limit,
    b.last4 AS bank_last4,
    b.opening_balance AS bank_opening_balance,
    br.cash_balance AS broker_cash_balance
FROM accounts a
LEFT JOIN account_credit_card_details cc ON cc.account_id = a.id
LEFT JOIN credit_limit_pools clp ON clp.id = cc.limit_pool_id
LEFT JOIN (
    SELECT ch.account_id, c.last4
    FROM cardholders ch
    JOIN cards c ON c.cardholder_id = ch.id
    WHERE ch.role = 'PRIMARY' AND ch.closed_on IS NULL AND c.closed_on IS NULL
) pc ON pc.account_id = a.id
LEFT JOIN account_bank_details b ON b.account_id = a.id
LEFT JOIN account_broker_details br ON br.account_id = a.id
WHERE a.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');
