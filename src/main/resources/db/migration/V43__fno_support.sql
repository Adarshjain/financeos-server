ALTER TABLE instruments ADD underlying_symbol VARCHAR2(50);
ALTER TABLE instruments ADD underlying_instrument_id VARCHAR2(36);
ALTER TABLE instruments ADD expiry_date DATE;
ALTER TABLE instruments ADD option_type VARCHAR2(10);
ALTER TABLE instruments ADD strike_price NUMBER(19,4);
ALTER TABLE instruments ADD lot_size NUMBER(10);
ALTER TABLE instruments ADD trading_symbol VARCHAR2(100);

CREATE INDEX idx_instruments_trading_symbol ON instruments(trading_symbol);
