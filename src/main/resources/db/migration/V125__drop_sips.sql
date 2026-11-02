-- Flyway Migration V125: SIPs were dropped as a feature; drop the table (constraints and indexes go with it).
-- Idempotent: ORA-00942 (table does not exist) is ignored.
BEGIN
  EXECUTE IMMEDIATE 'DROP TABLE sips CASCADE CONSTRAINTS PURGE';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
