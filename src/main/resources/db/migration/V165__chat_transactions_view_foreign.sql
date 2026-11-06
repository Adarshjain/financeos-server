-- V165__chat_transactions_view_foreign.sql
-- Ask the data leaves out a foreign charge waiting for its statement (amount NULL until the statement line fills the
-- rupee amount; V164), so no chat total counts it. Same definition as V122 plus t.amount IS NOT NULL.
--
-- Idempotent: CREATE OR REPLACE. Oracle only, like V70/V122 (tests run on H2 with Flyway off).

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
    t.category_key,
    tc.name AS category_name,
    t.subcategory_key,
    ts.name AS subcategory_name,
    tg.tag_names,
    a.id AS account_id,
    a.name AS account_name,
    a.type AS account_type,
    c.id AS card_id,
    c.last4 AS card_last4,
    ch.person_name AS card_holder,
    ch.relationship AS card_relationship
FROM transactions t
LEFT JOIN txn_categories tc ON tc.category_key = t.category_key
LEFT JOIN txn_subcategories ts ON ts.subcategory_key = t.subcategory_key
LEFT JOIN (
    SELECT x.transaction_id,
           LISTAGG(g.name, ', ') WITHIN GROUP (ORDER BY g.name) AS tag_names
    FROM transaction_tags x
    JOIN tags g ON g.id = x.tag_id
    GROUP BY x.transaction_id
) tg ON tg.transaction_id = t.id
LEFT JOIN accounts a ON a.id = t.account_id
LEFT JOIN cards c ON c.id = t.card_id
LEFT JOIN cardholders ch ON ch.id = c.cardholder_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')
  AND t.amount IS NOT NULL;
