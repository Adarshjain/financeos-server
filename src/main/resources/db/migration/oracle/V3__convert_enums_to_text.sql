-- This migration is a no-op for Oracle
-- PostgreSQL required converting native enums to TEXT for Hibernate compatibility
-- Oracle implementation never used enums - columns were created as VARCHAR2 from the start
-- This file exists only to maintain version number consistency with PostgreSQL migrations

-- No changes needed
