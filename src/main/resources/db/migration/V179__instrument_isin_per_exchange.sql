-- V179__instrument_isin_per_exchange.sql
-- A company listed on NSE and BSE has one catalog row per exchange with the same ISIN: the ISIN is unique per
-- exchange, not on its own.
--
-- Idempotent: the old unique index is dropped only when present, the new one created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'UK_INSTRUMENTS_ISIN';
    IF n > 0 THEN
        EXECUTE IMMEDIATE 'DROP INDEX uk_instruments_isin';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'UK_INSTRUMENTS_ISIN_EXCH';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX uk_instruments_isin_exch ON instruments (
            CASE WHEN isin IS NOT NULL THEN isin END,
            CASE WHEN isin IS NOT NULL THEN NVL(UPPER(exchange), ''-'') END
        )';
    END IF;
END;
/
