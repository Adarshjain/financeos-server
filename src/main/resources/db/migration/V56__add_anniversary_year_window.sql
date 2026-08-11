-- Anniversary-year reward windows: a 12-month window anchored on the card's
-- membership anniversary (e.g. spend-milestone years that don't align to Jan 1).
-- The anchor date lives on the account; the window becomes a selectable option
-- everywhere a cap/tier/milestone window is chosen.
ALTER TABLE accounts ADD reward_anniversary_date DATE;

-- Widen the window CHECK constraints to include ANNIVERSARY_YEAR (Oracle: drop + re-add).
ALTER TABLE reward_rules DROP CONSTRAINT chk_rr_cap_window;
ALTER TABLE reward_rules ADD CONSTRAINT chk_rr_cap_window CHECK (cap_window IN ('DAY','CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR','ANNIVERSARY_YEAR'));

ALTER TABLE reward_rules DROP CONSTRAINT chk_rr_tier_window;
ALTER TABLE reward_rules ADD CONSTRAINT chk_rr_tier_window CHECK (tier_window IN ('DAY','CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR','ANNIVERSARY_YEAR'));

ALTER TABLE reward_milestones DROP CONSTRAINT chk_rm_window;
ALTER TABLE reward_milestones ADD CONSTRAINT chk_rm_window CHECK (window_type IN ('CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR','ANNIVERSARY_YEAR'));

ALTER TABLE reward_cap_buckets DROP CONSTRAINT chk_rcb_window;
ALTER TABLE reward_cap_buckets ADD CONSTRAINT chk_rcb_window CHECK (window_type IN ('DAY','CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR','ANNIVERSARY_YEAR'));
