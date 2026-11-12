-- V185__corporate_action_suggestion_feed_factor.sql
-- The detector keeps the split factor the price feed showed for the ex-date next to the exchange's own ratio, so the
-- Corporate actions page can show both. Null when the feed had no split that day or was not consulted.
--
-- Idempotent: the column is added only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'CORPORATE_ACTION_SUGGESTIONS' AND column_name = 'FEED_FACTOR';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE corporate_action_suggestions ADD (feed_factor NUMBER(19,8))';
    END IF;
END;
/
