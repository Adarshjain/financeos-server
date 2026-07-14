-- Add optional 4-digit MCC column to transactions and category_rules tables
ALTER TABLE transactions ADD mcc VARCHAR2(4);
ALTER TABLE category_rules ADD mcc VARCHAR2(4);
