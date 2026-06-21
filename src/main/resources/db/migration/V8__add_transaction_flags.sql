-- Add monitoring and exclusion flags to transactions
ALTER TABLE transactions ADD is_under_monitoring NUMBER(1) DEFAULT 0 NOT NULL;
ALTER TABLE transactions ADD is_excluded NUMBER(1) DEFAULT 0 NOT NULL;
