-- Reward currency becomes orthogonal to accrual math: a rule computes a NUMBER
-- (percent, slab or tiered) and the reward type says whether that number is rupees
-- of cashback or reward points. The card carries a default; each rule can override.
-- Point-to-cash valuation is out of scope for now, so the per-rule point_value goes.

ALTER TABLE accounts ADD default_reward_type VARCHAR2(20) DEFAULT 'CASH' NOT NULL;
ALTER TABLE accounts ADD CONSTRAINT chk_acct_default_reward_type CHECK (default_reward_type IN ('CASH','POINTS'));

ALTER TABLE reward_rules ADD reward_type VARCHAR2(20);
-- Existing rules: SLAB was implicitly points, PERCENT implicitly cashback.
UPDATE reward_rules SET reward_type = CASE accrual_type WHEN 'SLAB' THEN 'POINTS' ELSE 'CASH' END;
ALTER TABLE reward_rules MODIFY reward_type DEFAULT 'CASH' NOT NULL;
ALTER TABLE reward_rules ADD CONSTRAINT chk_rr_reward_type CHECK (reward_type IN ('CASH','POINTS'));

-- A shared bucket's cap unit is its reward type; members must all match it.
ALTER TABLE reward_cap_buckets ADD reward_type VARCHAR2(20);
UPDATE reward_cap_buckets b SET reward_type = COALESCE(
    (SELECT MAX(r.reward_type) FROM reward_rules r WHERE r.cap_bucket_id = b.id), 'CASH');
ALTER TABLE reward_cap_buckets MODIFY reward_type DEFAULT 'CASH' NOT NULL;
ALTER TABLE reward_cap_buckets ADD CONSTRAINT chk_rcb_reward_type CHECK (reward_type IN ('CASH','POINTS'));

-- Safety net: the service layer has always forced one earn type per bucket, so this
-- should touch nothing — but any legacy member whose unit disagrees with its bucket
-- is detached rather than silently co-draining a mixed-unit ceiling.
UPDATE reward_rules r SET cap_bucket_id = NULL
WHERE cap_bucket_id IS NOT NULL
  AND reward_type <> (SELECT b.reward_type FROM reward_cap_buckets b WHERE b.id = r.cap_bucket_id);

ALTER TABLE reward_rules DROP COLUMN point_value;
