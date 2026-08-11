-- Milestones learn the reward-currency split (cash vs points payouts), a ONE_TIME
-- window for welcome-benefit style offers (single window = the active range), and a
-- shopper-chosen payout timing (counted at window end vs on the achievement date).
ALTER TABLE reward_milestones ADD reward_type VARCHAR2(20) DEFAULT 'CASH' NOT NULL;
ALTER TABLE reward_milestones ADD CONSTRAINT chk_rm_reward_type CHECK (reward_type IN ('CASH','POINTS'));

ALTER TABLE reward_milestones ADD payout_timing VARCHAR2(20) DEFAULT 'WINDOW_END' NOT NULL;
ALTER TABLE reward_milestones ADD CONSTRAINT chk_rm_payout_timing CHECK (payout_timing IN ('WINDOW_END','ON_ACHIEVEMENT'));

ALTER TABLE reward_milestones DROP CONSTRAINT chk_rm_window;
ALTER TABLE reward_milestones ADD CONSTRAINT chk_rm_window CHECK (window_type IN ('CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR','ANNIVERSARY_YEAR','ONE_TIME'));
