-- A reward rule's start date is optional: no active_from = active since forever.
-- (Fixes the trap where a freshly created rule silently ignored all past
-- transactions because active_from defaulted to creation day.)
ALTER TABLE reward_rules MODIFY active_from NULL;
