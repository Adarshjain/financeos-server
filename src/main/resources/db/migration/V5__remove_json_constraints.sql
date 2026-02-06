-- Remove strict JSON check constraints that conflict with Hibernate 6 mapping
-- These constraints are blocking transaction creation in Oracle 19c
ALTER TABLE transactions DROP CONSTRAINT chk_metadata_json;
ALTER TABLE investment_transactions DROP CONSTRAINT chk_inv_metadata_json;
