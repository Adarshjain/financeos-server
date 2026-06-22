-- Allow marking one dashboard per user as the default.
ALTER TABLE dashboards ADD is_default NUMBER(1) DEFAULT 0 NOT NULL;

-- Enforce at most one default dashboard per user. Non-default rows index to NULL
-- (Oracle does not store all-NULL keys), so only is_default = 1 rows are constrained.
CREATE UNIQUE INDEX uq_dashboards_one_default
    ON dashboards (CASE WHEN is_default = 1 THEN user_id ELSE NULL END);
