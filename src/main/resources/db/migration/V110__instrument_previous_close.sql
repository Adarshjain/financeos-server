-- V110__instrument_previous_close.sql
-- instrument_prices.previous_close: the prior session's close as the Yahoo feed reported it
-- (regularMarketPreviousClose / chart previous close), stored on Yahoo feed rows so the day change
-- is exact during market hours. Null for AMFI, MANUAL prices and every row stored before this
-- migration. Same type and precision as close.
--
-- DDL only. Idempotent: guards on user_tab_columns so a re-run is a no-op.

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_tab_columns
   WHERE table_name = 'INSTRUMENT_PRICES' AND column_name = 'PREVIOUS_CLOSE';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE instrument_prices ADD previous_close NUMBER(19, 4)';
  END IF;
END;
/
