-- V122__chat_views_categories.sql
-- Ask the data after the categories redesign (wave 7): v_chat_transactions carries the fixed-list
-- category and sub-category (key + label) and the tags (LISTAGG), instead of category_names (which
-- held the old per-user categories, now tags). The tag mapping and tag list views are renamed to what
-- they hold; v_chat_categories / v_chat_subcategories become the fixed list.
--
-- Idempotent: CREATE OR REPLACE for every view; the two retired views are dropped only if present.

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
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_transaction_tags AS
SELECT
    x.transaction_id,
    g.id AS tag_id,
    g.name AS tag_name
FROM transaction_tags x
JOIN tags g ON g.id = x.tag_id
JOIN transactions t ON t.id = x.transaction_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_tags AS
SELECT
    g.id,
    g.name
FROM tags g
WHERE g.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- The fixed global list (not user data, so no user filter).
CREATE OR REPLACE VIEW v_chat_categories AS
SELECT
    c.category_key,
    c.name,
    c.direction
FROM txn_categories c;

CREATE OR REPLACE VIEW v_chat_subcategories AS
SELECT
    s.subcategory_key,
    s.category_key,
    s.name,
    s.direction
FROM txn_subcategories s;

BEGIN
  BEGIN
    EXECUTE IMMEDIATE 'DROP VIEW v_chat_transaction_categories';
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE != -942 THEN RAISE; END IF; -- ORA-00942: already gone
  END;
END;
/

-- Grant SELECT on all V_CHAT_* views to chat_ro (skipped where the user doesn't exist, e.g. dev DBs).
BEGIN
  FOR v IN (SELECT view_name FROM user_views WHERE view_name LIKE 'V_CHAT_%') LOOP
    BEGIN
      EXECUTE IMMEDIATE 'GRANT SELECT ON ' || v.view_name || ' TO chat_ro';
    EXCEPTION WHEN OTHERS THEN
      IF SQLCODE != -1917 THEN RAISE; END IF; -- ORA-01917: user does not exist
    END;
  END LOOP;
END;
/
