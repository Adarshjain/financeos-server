-- Store JSON metadata in a BINARY column on Oracle.
--
-- Hibernate's JSON handling reads/writes JSON on Oracle via getBytes/setBytes
-- (binary), matching PostgreSQL's jsonb. The original CLOB (text) column was
-- incompatible with that path:
--   * reads failed with ORA-17023 (Unsupported feature: getBytes on a CLOB)
--   * inserting a null metadata failed with ORA-17004 (Invalid column type: 1111)
-- A BLOB column is binary and works across all supported Oracle versions.
--
-- NOTE: Oracle does not allow MODIFY-ing a CLOB column to BLOB, so this drops and
-- recreates the column, discarding any existing metadata values. If a higher
-- environment already holds metadata you must keep, replace this with a
-- data-preserving conversion (add a new BLOB column, copy via DBMS_LOB, then swap)
-- before deploying there.
ALTER TABLE transactions DROP COLUMN metadata;
ALTER TABLE transactions ADD metadata BLOB;

ALTER TABLE investment_transactions DROP COLUMN metadata;
ALTER TABLE investment_transactions ADD metadata BLOB;
