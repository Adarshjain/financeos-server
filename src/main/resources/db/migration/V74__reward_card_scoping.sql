-- Flyway Migration V74: Reward Card Scoping & Cap Scoping

-- 1. Axis 1 & 2: reward_rules card scoping and counter scoping
ALTER TABLE reward_rules ADD card_id VARCHAR2(36);
ALTER TABLE reward_rules ADD counter_scope VARCHAR2(20) DEFAULT 'ACCOUNT' NOT NULL;
ALTER TABLE reward_rules ADD CONSTRAINT fk_rr_card FOREIGN KEY (card_id) REFERENCES account_cards(id) ON DELETE SET NULL;
ALTER TABLE reward_rules ADD CONSTRAINT chk_rr_counter_scope CHECK (counter_scope IN ('ACCOUNT', 'PER_CARD'));

CREATE INDEX idx_rr_card ON reward_rules(card_id);

-- 2. Axis 3: reward_milestones card scoping
ALTER TABLE reward_milestones ADD card_id VARCHAR2(36);
ALTER TABLE reward_milestones ADD CONSTRAINT fk_rm_card FOREIGN KEY (card_id) REFERENCES account_cards(id) ON DELETE SET NULL;

CREATE INDEX idx_rm_card ON reward_milestones(card_id);
