-- Category rules gain a match_type: how merchant_key is matched against the
-- transaction's sourced description (MERCHANT_KEY = legacy normalized contains,
-- plus raw CONTAINS / STARTS_WITH / EXACT / REGEX).
ALTER TABLE category_rules ADD match_type VARCHAR2(20) DEFAULT 'MERCHANT_KEY' NOT NULL;

-- The same pattern text may now exist once per match type.
ALTER TABLE category_rules DROP CONSTRAINT uk_category_rules_user_merchant;
ALTER TABLE category_rules ADD CONSTRAINT uk_category_rules_user_key_type UNIQUE (user_id, merchant_key, match_type);
