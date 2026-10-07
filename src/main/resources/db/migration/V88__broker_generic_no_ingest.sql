-- V88__broker_generic_no_ingest.sql
-- Broker and generic (Wallet/Cash) accounts can never receive statements or Gmail alerts:
-- statement upload is bank/card only and Gmail resolves accounts by card last4, which neither
-- type has. Their ingest_from_date / last_statement_date columns were still writable through the
-- API (and V68 could grandfather a date onto one if gmail-sourced rows were ever moved there).
-- The API no longer accepts or returns these fields for the two types; this clears any stale
-- values so the data matches the contract.
--
-- Data-only UPDATE: DISABLE PARALLEL DML first (V68/V82 precedent — ORA-12839 on ADB).
-- Idempotent: the WHERE clause matches nothing on a second run.

ALTER SESSION DISABLE PARALLEL DML;

UPDATE accounts
   SET ingest_from_date = NULL,
       last_statement_date = NULL
 WHERE type IN ('broker', 'generic')
   AND (ingest_from_date IS NOT NULL OR last_statement_date IS NOT NULL);
