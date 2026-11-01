-- Freeze every existing exclusion: before the redesign is_excluded=1 was a user/auto decision; the category writer must never re-derive it. Idempotent.
UPDATE transactions SET exclude_set_by_user = 1 WHERE is_excluded = 1 AND exclude_set_by_user = 0;
