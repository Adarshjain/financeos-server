-- Per-account point value in INR config for pre-purchase rewards comparison.
ALTER TABLE accounts ADD point_value_inr NUMBER(12,4);
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_point_val_inr CHECK (point_value_inr IS NULL OR point_value_inr > 0);
