ALTER TABLE reward_cap_buckets ADD counter_scope VARCHAR2(20) DEFAULT 'ACCOUNT' NOT NULL;
ALTER TABLE reward_cap_buckets ADD CONSTRAINT chk_rcb_counter_scope
    CHECK (counter_scope IN ('ACCOUNT','PER_CARD'));
