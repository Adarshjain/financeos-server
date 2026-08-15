-- Convenience fee was captured on the transaction (V50) but only ever displayed:
-- it stayed inside the reward basis, so cards that post a surcharge and reward
-- nothing on it were over-earning. Fee treatment is per-rule, not per-card, because
-- an issuer can reward the surcharge on one category and not another.
-- Default INCLUDE preserves every existing rule's behavior; no history is recomputed.

ALTER TABLE reward_rules ADD fee_treatment VARCHAR2(20) DEFAULT 'INCLUDE' NOT NULL;
ALTER TABLE reward_rules ADD CONSTRAINT chk_rr_fee_treatment CHECK (fee_treatment IN ('INCLUDE','EXCLUDE_FEE'));
