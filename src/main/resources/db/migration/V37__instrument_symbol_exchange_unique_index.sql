-- Flyway Migration V37: Unique Index on Instrument (symbol, exchange) for non-null values

CREATE UNIQUE INDEX idx_instruments_sym_exch ON instruments (
    CASE WHEN symbol IS NOT NULL AND exchange IS NOT NULL THEN symbol END,
    CASE WHEN symbol IS NOT NULL AND exchange IS NOT NULL THEN exchange END
);
