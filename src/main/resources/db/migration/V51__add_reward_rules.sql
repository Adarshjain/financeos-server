-- User-configured reward rules: per-account, priority-ordered, effective-dated.
-- EXCLUSIVE rules form a first-match chain with cap fall-through; ADDITIVE rules
-- stack on top with their own caps. A 0%-rate EXCLUSIVE rule models an exclusion.
CREATE TABLE reward_rules (
    id                 VARCHAR2(36) PRIMARY KEY,
    user_id            VARCHAR2(36) NOT NULL,
    account_id         VARCHAR2(36) NOT NULL,
    name               VARCHAR2(200) NOT NULL,
    priority           NUMBER(10) NOT NULL,
    stacking           VARCHAR2(20) DEFAULT 'EXCLUSIVE' NOT NULL,
    active_from        DATE NOT NULL,
    active_to          DATE,
    merchant_pattern   VARCHAR2(500),
    merchant_match     VARCHAR2(20),
    min_amount         NUMBER(19,4),
    max_amount         NUMBER(19,4),
    emi_treatment      VARCHAR2(20) DEFAULT 'INCLUDE' NOT NULL,
    intl_treatment     VARCHAR2(20) DEFAULT 'INCLUDE' NOT NULL,
    accrual_type       VARCHAR2(20) NOT NULL,
    percent_rate       NUMBER(9,4),
    rounding           VARCHAR2(20),
    slab_size          NUMBER(19,4),
    points_per_slab    NUMBER(19,4),
    point_value        NUMBER(9,4),
    point_precision    NUMBER(2),
    per_txn_cap        NUMBER(19,4),
    period_cap         NUMBER(19,4),
    cap_window         VARCHAR2(20),
    on_cap_exhausted   VARCHAR2(20) DEFAULT 'FALL_THROUGH' NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_reward_rules_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_reward_rules_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT chk_rr_stacking CHECK (stacking IN ('EXCLUSIVE','ADDITIVE')),
    CONSTRAINT chk_rr_merchant_match CHECK (merchant_match IN ('CONTAINS','STARTS_WITH','EXACT','REGEX')),
    CONSTRAINT chk_rr_emi CHECK (emi_treatment IN ('INCLUDE','EXCLUDE_EMI','ONLY_EMI')),
    CONSTRAINT chk_rr_intl CHECK (intl_treatment IN ('INCLUDE','EXCLUDE_INTL','ONLY_INTL')),
    CONSTRAINT chk_rr_accrual CHECK (accrual_type IN ('PERCENT','SLAB')),
    CONSTRAINT chk_rr_rounding CHECK (rounding IN ('NONE','FLOOR_RUPEE','NEAREST_RUPEE')),
    CONSTRAINT chk_rr_cap_window CHECK (cap_window IN ('DAY','CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR')),
    CONSTRAINT chk_rr_on_cap CHECK (on_cap_exhausted IN ('FALL_THROUGH','STOP')),
    CONSTRAINT chk_rr_amount_band CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount),
    CONSTRAINT chk_rr_active_range CHECK (active_to IS NULL OR active_from < active_to)
);
CREATE INDEX idx_reward_rules_user ON reward_rules(user_id);
CREATE INDEX idx_reward_rules_account ON reward_rules(account_id);

CREATE TABLE reward_rule_categories (
    rule_id     VARCHAR2(36) NOT NULL,
    category_id VARCHAR2(36) NOT NULL,
    CONSTRAINT pk_reward_rule_categories PRIMARY KEY (rule_id, category_id),
    CONSTRAINT fk_rrc_rule FOREIGN KEY (rule_id) REFERENCES reward_rules(id) ON DELETE CASCADE,
    CONSTRAINT fk_rrc_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
);

CREATE TABLE reward_rule_mccs (
    rule_id VARCHAR2(36) NOT NULL,
    mcc     VARCHAR2(4) NOT NULL,
    CONSTRAINT pk_reward_rule_mccs PRIMARY KEY (rule_id, mcc),
    CONSTRAINT fk_rrm_rule FOREIGN KEY (rule_id) REFERENCES reward_rules(id) ON DELETE CASCADE
);

CREATE TABLE reward_rule_channels (
    rule_id VARCHAR2(36) NOT NULL,
    channel VARCHAR2(20) NOT NULL,
    CONSTRAINT pk_reward_rule_channels PRIMARY KEY (rule_id, channel),
    CONSTRAINT fk_rrch_rule FOREIGN KEY (rule_id) REFERENCES reward_rules(id) ON DELETE CASCADE,
    CONSTRAINT chk_rrch_channel CHECK (channel IN ('ONLINE','POS','UPI','CONTACTLESS','OTHER'))
);

CREATE TABLE reward_rule_days (
    rule_id     VARCHAR2(36) NOT NULL,
    day_of_week VARCHAR2(10) NOT NULL,
    CONSTRAINT pk_reward_rule_days PRIMARY KEY (rule_id, day_of_week),
    CONSTRAINT fk_rrd_rule FOREIGN KEY (rule_id) REFERENCES reward_rules(id) ON DELETE CASCADE,
    CONSTRAINT chk_rrd_day CHECK (day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'))
);
