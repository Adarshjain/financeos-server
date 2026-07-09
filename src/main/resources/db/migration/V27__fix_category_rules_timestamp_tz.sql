-- V26 created the category_rules timestamp columns as plain TIMESTAMP, but the entity maps them as
-- java.time.Instant, which Hibernate 6 reads as TIMESTAMP_UTC (OffsetDateTime) — Oracle rejects that
-- read with ORA-18716 on a timezone-less column. Every other Instant column in the schema (see V1)
-- is TIMESTAMP WITH TIME ZONE. Oracle cannot MODIFY a populated column's datatype, so: add, copy, swap.

ALTER TABLE category_rules ADD created_at_tz TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE category_rules ADD updated_at_tz TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE category_rules ADD last_applied_at_tz TIMESTAMP WITH TIME ZONE NULL;

-- Hibernate wrote these values normalized to UTC
UPDATE category_rules SET
    created_at_tz      = CASE WHEN created_at      IS NOT NULL THEN FROM_TZ(created_at, 'UTC') ELSE created_at_tz END,
    updated_at_tz      = CASE WHEN updated_at      IS NOT NULL THEN FROM_TZ(updated_at, 'UTC') ELSE updated_at_tz END,
    last_applied_at_tz = CASE WHEN last_applied_at IS NOT NULL THEN FROM_TZ(last_applied_at, 'UTC') END;

ALTER TABLE category_rules DROP COLUMN created_at;
ALTER TABLE category_rules DROP COLUMN updated_at;
ALTER TABLE category_rules DROP COLUMN last_applied_at;

ALTER TABLE category_rules RENAME COLUMN created_at_tz TO created_at;
ALTER TABLE category_rules RENAME COLUMN updated_at_tz TO updated_at;
ALTER TABLE category_rules RENAME COLUMN last_applied_at_tz TO last_applied_at;
