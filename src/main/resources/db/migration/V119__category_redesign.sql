-- V119__category_redesign.sql
-- Category redesign, wave 1a (owner-approved 2026-11-01): every transaction gets ONE category from a
-- fixed global list (txn_categories) and optionally a sub-category (txn_subcategories). The old
-- per-user categories become per-user TAGS (same ids, same transactions). All user rules are wiped
-- (backed up first) and re-shaped to point at one category/sub-category.
--
-- Backups category_rules_bak_20261101 and category_rule_categories_bak_20261101 are dropped by hand
-- after 30 days (2026-12-01).
--
-- The seed mirrors src/main/resources/categorize/taxonomy.json (the source of truth; a test checks
-- that the two agree). category_set_at is TIMESTAMP WITH TIME ZONE (Instant convention, see V71).
--
-- Idempotent: every DDL swallows its "already done" error, the seed is MERGE-if-missing, and the
-- rule wipe only runs while category_rules still has its old `source` column (i.e. once).

DECLARE
    v_wipe NUMBER;

    PROCEDURE ddl(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT 0, p_err3 NUMBER DEFAULT 0) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE NOT IN (p_err1, p_err2, p_err3) THEN
                RAISE;
            END IF;
    END;

    -- Dynamic SQL: the tables do not exist when this block is compiled.
    PROCEDURE cat(p_key VARCHAR2, p_name VARCHAR2, p_dir VARCHAR2, p_out NUMBER, p_in NUMBER,
                  p_icon VARCHAR2, p_excl NUMBER) IS
    BEGIN
        EXECUTE IMMEDIATE 'MERGE INTO txn_categories t USING (SELECT :1 AS k FROM dual) s ON (t.category_key = s.k) '
            || 'WHEN NOT MATCHED THEN INSERT (category_key, name, direction, out_order, in_order, icon, excluded_by_default) '
            || 'VALUES (:2, :3, :4, :5, :6, :7, :8)'
            USING p_key, p_key, p_name, p_dir, p_out, p_in, p_icon, p_excl;
    END;

    PROCEDURE sub(p_key VARCHAR2, p_cat VARCHAR2, p_name VARCHAR2, p_dir VARCHAR2, p_order NUMBER) IS
    BEGIN
        EXECUTE IMMEDIATE 'MERGE INTO txn_subcategories t USING (SELECT :1 AS k FROM dual) s ON (t.subcategory_key = s.k) '
            || 'WHEN NOT MATCHED THEN INSERT (subcategory_key, category_key, name, direction, sort_order) '
            || 'VALUES (:2, :3, :4, :5, :6)'
            USING p_key, p_key, p_cat, p_name, p_dir, p_order;
    END;
BEGIN
    -- 1. Fixed global list.
    ddl('CREATE TABLE txn_categories ('
        || 'category_key VARCHAR2(40) NOT NULL, '
        || 'name VARCHAR2(100) NOT NULL, '
        || 'direction VARCHAR2(4) NOT NULL, '
        || 'out_order NUMBER NULL, '
        || 'in_order NUMBER NULL, '
        || 'icon VARCHAR2(60) NOT NULL, '
        || 'excluded_by_default NUMBER(1) DEFAULT 0 NOT NULL, '
        || 'CONSTRAINT pk_txn_categories PRIMARY KEY (category_key), '
        || 'CONSTRAINT chk_txn_categories_dir CHECK (direction IN (''OUT'', ''IN'', ''BOTH'')))', -955);
    ddl('CREATE TABLE txn_subcategories ('
        || 'subcategory_key VARCHAR2(80) NOT NULL, '
        || 'category_key VARCHAR2(40) NOT NULL, '
        || 'name VARCHAR2(100) NOT NULL, '
        || 'direction VARCHAR2(4) NOT NULL, '
        || 'sort_order NUMBER NOT NULL, '
        || 'CONSTRAINT pk_txn_subcategories PRIMARY KEY (subcategory_key), '
        || 'CONSTRAINT fk_txn_subcat_cat FOREIGN KEY (category_key) REFERENCES txn_categories(category_key), '
        || 'CONSTRAINT chk_txn_subcat_dir CHECK (direction IN (''OUT'', ''IN'', ''BOTH'')))', -955);
    ddl('CREATE INDEX idx_txn_subcat_cat ON txn_subcategories(category_key)', -955, -1408);

    -- Seed (generated from taxonomy.json).
    cat('food', 'Food', 'OUT', 1, NULL, 'cat-fork-knife', 0);
    cat('groceries', 'Groceries', 'OUT', 2, NULL, 'cat-shopping-cart', 0);
    cat('transport', 'Transport', 'OUT', 3, NULL, 'cat-taxi', 0);
    cat('travel', 'Travel', 'OUT', 4, NULL, 'cat-airplane-tilt', 0);
    cat('shopping', 'Shopping', 'OUT', 5, NULL, 'cat-shopping-bag', 0);
    cat('bills', 'Bills', 'OUT', 6, NULL, 'cat-receipt', 0);
    cat('subscriptions', 'Subscriptions', 'OUT', 7, NULL, 'cat-repeat', 0);
    cat('health', 'Health', 'OUT', 8, NULL, 'cat-first-aid-kit', 0);
    cat('fitness', 'Fitness', 'OUT', 9, NULL, 'cat-barbell', 0);
    cat('personal', 'Personal', 'OUT', 10, NULL, 'cat-sparkle', 0);
    cat('services', 'Services', 'OUT', 11, NULL, 'cat-wrench', 0);
    cat('entertainment', 'Entertainment', 'OUT', 12, NULL, 'cat-film-slate', 0);
    cat('education', 'Education', 'OUT', 13, NULL, 'cat-graduation-cap', 0);
    cat('pets', 'Pets', 'OUT', 14, NULL, 'cat-paw-print', 0);
    cat('family', 'Family', 'OUT', 15, NULL, 'cat-users-three', 0);
    cat('gifts', 'Gifts', 'BOTH', 16, 8, 'gift', 0);
    cat('donations', 'Donations', 'OUT', 17, NULL, 'cat-hand-heart', 0);
    cat('emi', 'EMI', 'OUT', 18, NULL, 'cat-bank', 0);
    cat('insurance', 'Insurance', 'OUT', 19, NULL, 'cat-shield-check', 0);
    cat('taxes', 'Taxes', 'OUT', 20, NULL, 'cat-scales', 0);
    cat('investments', 'Investments', 'OUT', 21, NULL, 'chart-line-up', 0);
    cat('savings', 'Savings', 'OUT', 22, NULL, 'cat-piggy-bank', 0);
    cat('lent', 'Lent', 'OUT', 23, NULL, 'cat-hand-coins', 0);
    cat('repaid', 'Repaid', 'BOTH', 24, 10, 'cat-arrow-counter-clockwise', 0);
    cat('cash', 'Cash', 'OUT', 25, NULL, 'cat-money', 0);
    cat('fees', 'Fees', 'OUT', 26, NULL, 'cat-coins', 0);
    cat('self_transfer', 'Self transfer', 'BOTH', 27, 12, 'cat-arrows-left-right', 1);
    cat('card_bill', 'Card bill', 'BOTH', 28, 13, 'cat-credit-card', 1);
    cat('top_up', 'Top-up', 'OUT', 29, NULL, 'wallet', 1);
    cat('other', 'Other', 'BOTH', 30, 14, 'cat-dots-three-outline', 0);
    cat('salary', 'Salary', 'IN', NULL, 1, 'cat-briefcase', 0);
    cat('earnings', 'Earnings', 'IN', NULL, 2, 'cat-storefront', 0);
    cat('interest', 'Interest', 'IN', NULL, 3, 'cat-percent', 0);
    cat('dividends', 'Dividends', 'IN', NULL, 4, 'cat-chart-pie-slice', 0);
    cat('redemption', 'Redemption', 'IN', NULL, 5, 'cat-arrow-circle-down', 0);
    cat('refund', 'Refund', 'IN', NULL, 6, 'cat-arrow-u-up-left', 0);
    cat('cashback', 'Cashback', 'IN', NULL, 7, 'cat-confetti', 0);
    cat('borrowed', 'Borrowed', 'IN', NULL, 9, 'cat-hand-arrow-down', 0);
    cat('cash_deposit', 'Cash deposit', 'IN', NULL, 11, 'cat-money', 0);
    sub('food.dining', 'food', 'Dining', 'OUT', 1);
    sub('food.delivery', 'food', 'Delivery', 'OUT', 2);
    sub('food.cafes', 'food', 'Cafés', 'OUT', 3);
    sub('food.drinks', 'food', 'Drinks', 'OUT', 4);
    sub('food.snacks', 'food', 'Snacks', 'OUT', 5);
    sub('groceries.supermarket', 'groceries', 'Supermarket', 'OUT', 1);
    sub('groceries.fruits_veg', 'groceries', 'Fruits & veg', 'OUT', 2);
    sub('groceries.meat_fish', 'groceries', 'Meat & fish', 'OUT', 3);
    sub('groceries.dairy', 'groceries', 'Dairy', 'OUT', 4);
    sub('groceries.bakery', 'groceries', 'Bakery', 'OUT', 5);
    sub('groceries.household', 'groceries', 'Household', 'OUT', 6);
    sub('transport.cabs', 'transport', 'Cabs', 'OUT', 1);
    sub('transport.autos', 'transport', 'Autos', 'OUT', 2);
    sub('transport.metro', 'transport', 'Metro', 'OUT', 3);
    sub('transport.local_bus', 'transport', 'Local bus', 'OUT', 4);
    sub('transport.fuel', 'transport', 'Fuel', 'OUT', 5);
    sub('transport.parking', 'transport', 'Parking', 'OUT', 6);
    sub('transport.tolls', 'transport', 'Tolls', 'OUT', 7);
    sub('transport.vehicle_service', 'transport', 'Vehicle service', 'OUT', 8);
    sub('transport.fines', 'transport', 'Fines', 'OUT', 9);
    sub('travel.flights', 'travel', 'Flights', 'OUT', 1);
    sub('travel.trains', 'travel', 'Trains', 'OUT', 2);
    sub('travel.buses', 'travel', 'Buses', 'OUT', 3);
    sub('travel.hotels', 'travel', 'Hotels', 'OUT', 4);
    sub('travel.activities', 'travel', 'Activities', 'OUT', 5);
    sub('travel.visa', 'travel', 'Visa', 'OUT', 6);
    sub('shopping.general', 'shopping', 'General', 'OUT', 1);
    sub('shopping.clothes', 'shopping', 'Clothes', 'OUT', 2);
    sub('shopping.electronics', 'shopping', 'Electronics', 'OUT', 3);
    sub('shopping.home', 'shopping', 'Home', 'OUT', 4);
    sub('shopping.beauty', 'shopping', 'Beauty', 'OUT', 5);
    sub('shopping.books', 'shopping', 'Books', 'OUT', 6);
    sub('shopping.jewellery', 'shopping', 'Jewellery', 'OUT', 7);
    sub('shopping.kids', 'shopping', 'Kids', 'OUT', 8);
    sub('shopping.eyewear', 'shopping', 'Eyewear', 'OUT', 9);
    sub('bills.rent', 'bills', 'Rent', 'OUT', 1);
    sub('bills.maintenance', 'bills', 'Maintenance', 'OUT', 2);
    sub('bills.electricity', 'bills', 'Electricity', 'OUT', 3);
    sub('bills.water', 'bills', 'Water', 'OUT', 4);
    sub('bills.gas', 'bills', 'Gas', 'OUT', 5);
    sub('bills.mobile', 'bills', 'Mobile', 'OUT', 6);
    sub('bills.internet', 'bills', 'Internet', 'OUT', 7);
    sub('bills.tv', 'bills', 'TV', 'OUT', 8);
    sub('bills.house_help', 'bills', 'House help', 'OUT', 9);
    sub('subscriptions.streaming', 'subscriptions', 'Streaming', 'OUT', 1);
    sub('subscriptions.music', 'subscriptions', 'Music', 'OUT', 2);
    sub('subscriptions.software', 'subscriptions', 'Software', 'OUT', 3);
    sub('subscriptions.cloud', 'subscriptions', 'Cloud', 'OUT', 4);
    sub('subscriptions.news', 'subscriptions', 'News', 'OUT', 5);
    sub('subscriptions.memberships', 'subscriptions', 'Memberships', 'OUT', 6);
    sub('health.doctor', 'health', 'Doctor', 'OUT', 1);
    sub('health.hospital', 'health', 'Hospital', 'OUT', 2);
    sub('health.medicines', 'health', 'Medicines', 'OUT', 3);
    sub('health.lab_tests', 'health', 'Lab tests', 'OUT', 4);
    sub('health.dental', 'health', 'Dental', 'OUT', 5);
    sub('health.eye_care', 'health', 'Eye care', 'OUT', 6);
    sub('fitness.gym', 'fitness', 'Gym', 'OUT', 1);
    sub('fitness.sports', 'fitness', 'Sports', 'OUT', 2);
    sub('fitness.classes', 'fitness', 'Classes', 'OUT', 3);
    sub('fitness.equipment', 'fitness', 'Equipment', 'OUT', 4);
    sub('fitness.nutrition', 'fitness', 'Nutrition', 'OUT', 5);
    sub('personal.salon', 'personal', 'Salon', 'OUT', 1);
    sub('personal.spa', 'personal', 'Spa', 'OUT', 2);
    sub('personal.hobbies', 'personal', 'Hobbies', 'OUT', 3);
    sub('personal.therapy', 'personal', 'Therapy', 'OUT', 4);
    sub('personal.vices', 'personal', 'Vices', 'OUT', 5);
    sub('services.laundry', 'services', 'Laundry', 'OUT', 1);
    sub('services.repairs', 'services', 'Repairs', 'OUT', 2);
    sub('services.cleaning', 'services', 'Cleaning', 'OUT', 3);
    sub('services.courier', 'services', 'Courier', 'OUT', 4);
    sub('services.printing', 'services', 'Printing', 'OUT', 5);
    sub('services.professional', 'services', 'Professional', 'OUT', 6);
    sub('services.government', 'services', 'Government', 'OUT', 7);
    sub('entertainment.movies', 'entertainment', 'Movies', 'OUT', 1);
    sub('entertainment.events', 'entertainment', 'Events', 'OUT', 2);
    sub('entertainment.gaming', 'entertainment', 'Gaming', 'OUT', 3);
    sub('entertainment.outings', 'entertainment', 'Outings', 'OUT', 4);
    sub('education.school', 'education', 'School', 'OUT', 1);
    sub('education.college', 'education', 'College', 'OUT', 2);
    sub('education.courses', 'education', 'Courses', 'OUT', 3);
    sub('education.coaching', 'education', 'Coaching', 'OUT', 4);
    sub('education.exams', 'education', 'Exams', 'OUT', 5);
    sub('pets.pet_food', 'pets', 'Pet food', 'OUT', 1);
    sub('pets.vet', 'pets', 'Vet', 'OUT', 2);
    sub('pets.pet_care', 'pets', 'Pet care', 'OUT', 3);
    sub('family.parents', 'family', 'Parents', 'OUT', 1);
    sub('family.spouse', 'family', 'Spouse', 'OUT', 2);
    sub('family.kids', 'family', 'Kids', 'OUT', 3);
    sub('family.pocket_money', 'family', 'Pocket money', 'OUT', 4);
    sub('donations.charity', 'donations', 'Charity', 'OUT', 1);
    sub('donations.religious', 'donations', 'Religious', 'OUT', 2);
    sub('emi.home_loan', 'emi', 'Home loan', 'OUT', 1);
    sub('emi.car_loan', 'emi', 'Car loan', 'OUT', 2);
    sub('emi.bike_loan', 'emi', 'Bike loan', 'OUT', 3);
    sub('emi.personal_loan', 'emi', 'Personal loan', 'OUT', 4);
    sub('emi.education_loan', 'emi', 'Education loan', 'OUT', 5);
    sub('emi.consumer_emi', 'emi', 'Consumer EMI', 'OUT', 6);
    sub('insurance.health', 'insurance', 'Health', 'OUT', 1);
    sub('insurance.life', 'insurance', 'Life', 'OUT', 2);
    sub('insurance.vehicle', 'insurance', 'Vehicle', 'OUT', 3);
    sub('insurance.home', 'insurance', 'Home', 'OUT', 4);
    sub('insurance.travel', 'insurance', 'Travel', 'OUT', 5);
    sub('taxes.income_tax', 'taxes', 'Income tax', 'OUT', 1);
    sub('taxes.property_tax', 'taxes', 'Property tax', 'OUT', 2);
    sub('taxes.gst', 'taxes', 'GST', 'OUT', 3);
    sub('investments.mutual_funds', 'investments', 'Mutual funds', 'OUT', 1);
    sub('investments.stocks', 'investments', 'Stocks', 'OUT', 2);
    sub('investments.fixed_deposit', 'investments', 'Fixed deposit', 'OUT', 3);
    sub('investments.recurring_deposit', 'investments', 'Recurring deposit', 'OUT', 4);
    sub('investments.ppf', 'investments', 'PPF', 'OUT', 5);
    sub('investments.nps', 'investments', 'NPS', 'OUT', 6);
    sub('investments.gold', 'investments', 'Gold', 'OUT', 7);
    sub('investments.bonds', 'investments', 'Bonds', 'OUT', 8);
    sub('investments.crypto', 'investments', 'Crypto', 'OUT', 9);
    sub('fees.bank_charges', 'fees', 'Bank charges', 'OUT', 1);
    sub('fees.card_fees', 'fees', 'Card fees', 'OUT', 2);
    sub('fees.late_fees', 'fees', 'Late fees', 'OUT', 3);
    sub('fees.interest', 'fees', 'Interest', 'OUT', 4);
    sub('fees.forex_markup', 'fees', 'Forex markup', 'OUT', 5);
    sub('card_bill.credit_card', 'card_bill', 'Credit card', 'OUT', 1);
    sub('card_bill.pay_later', 'card_bill', 'Pay later', 'OUT', 2);
    sub('top_up.upi_lite', 'top_up', 'UPI Lite', 'OUT', 1);
    sub('top_up.wallet', 'top_up', 'Wallet', 'OUT', 2);
    sub('top_up.gift_card', 'top_up', 'Gift card', 'OUT', 3);
    sub('other.verification', 'other', 'Verification', 'BOTH', 1);
    sub('other.tips', 'other', 'Tips', 'OUT', 2);
    sub('other.forex', 'other', 'Forex', 'OUT', 3);
    sub('salary.salary', 'salary', 'Salary', 'IN', 1);
    sub('salary.bonus', 'salary', 'Bonus', 'IN', 2);
    sub('salary.reimbursement', 'salary', 'Reimbursement', 'IN', 3);
    sub('earnings.freelance', 'earnings', 'Freelance', 'IN', 1);
    sub('earnings.business', 'earnings', 'Business', 'IN', 2);
    sub('earnings.rent', 'earnings', 'Rent', 'IN', 3);
    sub('interest.savings_account', 'interest', 'Savings account', 'IN', 1);
    sub('interest.deposits', 'interest', 'Deposits', 'IN', 2);
    sub('interest.bonds', 'interest', 'Bonds', 'IN', 3);
    sub('redemption.mutual_funds', 'redemption', 'Mutual funds', 'IN', 1);
    sub('redemption.stocks', 'redemption', 'Stocks', 'IN', 2);
    sub('redemption.deposits', 'redemption', 'Deposits', 'IN', 3);
    sub('redemption.insurance_payout', 'redemption', 'Insurance payout', 'IN', 4);
    sub('cashback.card_cashback', 'cashback', 'Card cashback', 'IN', 1);
    sub('cashback.app_cashback', 'cashback', 'App cashback', 'IN', 2);

    -- 2. Backup before the wipe (only the first run finds the old tables; later runs swallow).
    ddl('CREATE TABLE category_rules_bak_20261101 AS SELECT * FROM category_rules', -955, -942);
    ddl('CREATE TABLE category_rule_categories_bak_20261101 AS SELECT * FROM category_rule_categories', -955, -942);

    -- 3. Per-user categories become tags. Ids unchanged; FKs (reward_rule_categories, join table)
    --    follow the rename.
    ddl('ALTER TABLE categories RENAME TO tags', -942, -955);
    ddl('ALTER TABLE transaction_categories RENAME TO transaction_tags', -942, -955);
    ddl('ALTER TABLE transaction_tags RENAME COLUMN category_id TO tag_id', -957, -942);

    -- 4. The transaction's one category.
    ddl('ALTER TABLE transactions ADD category_key VARCHAR2(40)', -1430);
    ddl('ALTER TABLE transactions ADD subcategory_key VARCHAR2(80)', -1430);
    ddl('ALTER TABLE transactions ADD category_source VARCHAR2(10)', -1430);
    ddl('ALTER TABLE transactions ADD category_set_at TIMESTAMP WITH TIME ZONE', -1430);
    ddl('ALTER TABLE transactions ADD exclude_set_by_user NUMBER(1) DEFAULT 0 NOT NULL', -1430);
    ddl('ALTER TABLE transactions ADD CONSTRAINT fk_txn_category FOREIGN KEY (category_key) '
        || 'REFERENCES txn_categories(category_key)', -2264, -2275);
    ddl('ALTER TABLE transactions ADD CONSTRAINT fk_txn_subcategory FOREIGN KEY (subcategory_key) '
        || 'REFERENCES txn_subcategories(subcategory_key)', -2264, -2275);
    ddl('ALTER TABLE transactions ADD CONSTRAINT chk_txn_category_source CHECK '
        || '(category_source IS NULL OR category_source IN (''AUTO'', ''LINK'', ''RULE'', ''USER''))', -2264, -2275);
    ddl('CREATE INDEX idx_txn_user_category ON transactions(user_id, category_key)', -955, -1408);
    ddl('CREATE INDEX idx_txn_subcategory ON transactions(subcategory_key)', -955, -1408);

    -- 5. Review reasons: CATEGORY_UNVERIFIED goes away. Rows that were in review only for it are
    --    promoted so they do not sit in the queue with no reason.
    EXECUTE IMMEDIATE 'UPDATE transactions t SET review_type = ''AUTO_REVIEWED'', reviewed_at = CURRENT_TIMESTAMP '
        || 'WHERE t.review_type = ''NEEDS_REVIEW'' '
        || 'AND EXISTS (SELECT 1 FROM transaction_review_reasons r WHERE r.transaction_id = t.id AND r.reason = ''CATEGORY_UNVERIFIED'') '
        || 'AND NOT EXISTS (SELECT 1 FROM transaction_review_reasons r WHERE r.transaction_id = t.id AND r.reason <> ''CATEGORY_UNVERIFIED'')';
    EXECUTE IMMEDIATE 'DELETE FROM transaction_review_reasons WHERE reason = ''CATEGORY_UNVERIFIED''';

    -- 6. Wipe the rules (once: only while the old `source` column is still there) and reshape.
    SELECT COUNT(*) INTO v_wipe FROM user_tab_columns WHERE table_name = 'CATEGORY_RULES' AND column_name = 'SOURCE';
    IF v_wipe > 0 THEN
        EXECUTE IMMEDIATE 'UPDATE transactions SET applied_rule_id = NULL WHERE applied_rule_id IS NOT NULL';
        ddl('DROP TABLE category_rule_categories', -942);
        EXECUTE IMMEDIATE 'DELETE FROM category_rules';
        ddl('ALTER TABLE category_rules DROP COLUMN source', -904);
        ddl('ALTER TABLE category_rules DROP COLUMN verified', -904);
    END IF;
    ddl('ALTER TABLE category_rules ADD category_key VARCHAR2(40) NOT NULL', -1430);
    ddl('ALTER TABLE category_rules ADD subcategory_key VARCHAR2(80)', -1430);
    ddl('ALTER TABLE category_rules ADD CONSTRAINT fk_rule_category FOREIGN KEY (category_key) '
        || 'REFERENCES txn_categories(category_key)', -2264, -2275);
    ddl('ALTER TABLE category_rules ADD CONSTRAINT fk_rule_subcategory FOREIGN KEY (subcategory_key) '
        || 'REFERENCES txn_subcategories(subcategory_key)', -2264, -2275);
    ddl('ALTER TABLE category_rules MODIFY match_type DEFAULT ''CONTAINS''', 0);
    ddl('ALTER TABLE category_rules ADD CONSTRAINT chk_rule_match_type CHECK '
        || '(match_type IN (''CONTAINS'', ''STARTS_WITH'', ''EXACT'', ''REGEX''))', -2264, -2275);
END;
/

-- 7. Chat views that referenced the renamed tables (the rename left them INVALID). Names and
--    columns unchanged; grants survive CREATE OR REPLACE. Wave 7 reshapes them.
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
           LISTAGG(g.name, ', ') WITHIN GROUP (ORDER BY g.name) AS category_names
    FROM transaction_tags tc
    JOIN tags g ON g.id = tc.tag_id
    GROUP BY tc.transaction_id
) cat ON cat.transaction_id = t.id
LEFT JOIN accounts a ON a.id = t.account_id
LEFT JOIN cards c ON c.id = t.card_id
LEFT JOIN cardholders ch ON ch.id = c.cardholder_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_transaction_categories AS
SELECT
    tc.transaction_id,
    g.id AS category_id,
    g.name AS category_name
FROM transaction_tags tc
JOIN tags g ON g.id = tc.tag_id
JOIN transactions t ON t.id = tc.transaction_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_categories AS
SELECT
    g.id,
    g.name
FROM tags g
WHERE g.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');
