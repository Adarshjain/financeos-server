ALTER SESSION DISABLE PARALLEL DML;

-- Grandfather opt-in for pre-existing accounts with Gmail transactions
UPDATE accounts a
SET a.ingest_from_date = (
    SELECT TRUNC(MIN(t.transaction_date))
    FROM transactions t
    WHERE t.account_id = a.id
      AND t.source IN ('gmail', 'gmail_transaction_alert', 'gmail_statement')
)
WHERE a.ingest_from_date IS NULL
  AND EXISTS (
      SELECT 1
      FROM transactions t
      WHERE t.account_id = a.id
        AND t.source IN ('gmail', 'gmail_transaction_alert', 'gmail_statement')
  );
