-- Tiered (marginal-rate) accrual: the rule's rate steps up/down based on the
-- running matched-spend total within tier_window (IDFC-10X / IndusInd-Tiger shape).
-- tiers is JSON: ordered [{"upTo": 20000, "rate": 3}, {"upTo": null, "rate": 30}]
-- where rate = percentRate for PERCENT rules, pointsPerSlab for SLAB rules.
ALTER TABLE reward_rules ADD tier_window VARCHAR2(20);
ALTER TABLE reward_rules ADD tiers CLOB;
ALTER TABLE reward_rules ADD CONSTRAINT chk_rr_tier_window CHECK (tier_window IN ('DAY','CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR'));
