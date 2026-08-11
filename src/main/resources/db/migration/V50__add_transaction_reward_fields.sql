-- Reward-engine transaction enrichment: bank posting date, checkout adjustments
-- (instant discount was never charged; convenience fee is a labeled portion of amount),
-- and optional flags that reward rules can match on.
ALTER TABLE transactions ADD settlement_date DATE;
ALTER TABLE transactions ADD instant_discount NUMBER(19,4);
ALTER TABLE transactions ADD convenience_fee NUMBER(19,4);
ALTER TABLE transactions ADD channel VARCHAR2(20);
ALTER TABLE transactions ADD is_emi NUMBER(1);
ALTER TABLE transactions ADD is_international NUMBER(1);

ALTER TABLE transactions ADD CONSTRAINT chk_txn_channel CHECK (channel IN ('ONLINE','POS','UPI','CONTACTLESS','OTHER'));
ALTER TABLE transactions ADD CONSTRAINT chk_txn_instant_discount CHECK (instant_discount >= 0);
ALTER TABLE transactions ADD CONSTRAINT chk_txn_convenience_fee CHECK (convenience_fee >= 0);
