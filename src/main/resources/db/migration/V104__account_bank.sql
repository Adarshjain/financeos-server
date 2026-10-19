-- V104__account_bank.sql
-- accounts.bank: the issuing bank of a bank account or credit card, a BankCatalog id (e.g. 'hdfc').
-- NULL = not set. Used by the Gmail resolver to break a same-last4 tie by the bank named in the email.
--
-- Idempotent: ADD swallows ORA-01430 (column already exists).

DECLARE
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE accounts ADD bank VARCHAR2(32)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -1430 THEN
            RAISE;
        END IF;
END;
/
