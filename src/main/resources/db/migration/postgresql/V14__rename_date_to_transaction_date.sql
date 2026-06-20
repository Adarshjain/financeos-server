-- Align the Postgres column name with the JPA entities and the Oracle schema.
-- The Transaction / InvestmentTransaction entities map `transaction_date`, and the
-- Oracle migrations create `transaction_date`, but the Postgres schema still used the
-- original `date` column. This mismatch broke every transaction read/write on Postgres
-- (ERROR: column "transaction_date" does not exist).
ALTER TABLE transactions RENAME COLUMN date TO transaction_date;
ALTER TABLE investment_transactions RENAME COLUMN date TO transaction_date;
