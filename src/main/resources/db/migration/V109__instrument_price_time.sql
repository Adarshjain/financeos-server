-- V109__instrument_price_time.sql
-- instrument_prices.price_time: the instant a feed quote was last traded (Yahoo regularMarketTime).
-- Null for AMFI (end-of-day NAV, date only), MANUAL prices and every row stored before this
-- migration. as_of stays the IST calendar date; price_time is purely additive.
--
-- DDL only. Idempotent: guards on user_tab_columns so a re-run is a no-op.

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_tab_columns
   WHERE table_name = 'INSTRUMENT_PRICES' AND column_name = 'PRICE_TIME';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE instrument_prices ADD price_time TIMESTAMP WITH TIME ZONE';
  END IF;
END;
/
