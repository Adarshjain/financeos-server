-- V48__lendings_ledger.sql
-- Redesign lendings to a two-way ledger per counterparty

DROP TABLE lending_repayments;

ALTER TABLE lendings DROP COLUMN status;

ALTER TABLE lendings RENAME COLUMN lend_date TO entry_date;
