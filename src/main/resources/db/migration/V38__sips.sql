-- Flyway Migration V38: SIPs Table for Systematic Investment Plan tracking

CREATE TABLE sips (
    id VARCHAR2(36) NOT NULL,
    user_id VARCHAR2(36) NOT NULL,
    broker_account_id VARCHAR2(36) NOT NULL,
    instrument_id VARCHAR2(36) NOT NULL,
    amount NUMBER(19,2) NOT NULL,
    frequency VARCHAR2(20) NOT NULL,
    day_of_month NUMBER(10,0) NULL,
    start_date DATE NOT NULL,
    end_date DATE NULL,
    active NUMBER(1,0) DEFAULT 1 NOT NULL,
    notes VARCHAR2(500) NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_sips PRIMARY KEY (id),
    CONSTRAINT fk_sips_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_sips_broker_account FOREIGN KEY (broker_account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_sips_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id),
    CONSTRAINT chk_sips_frequency CHECK (frequency IN ('weekly', 'monthly'))
);

CREATE INDEX idx_sips_user_id ON sips(user_id);
CREATE INDEX idx_sips_broker_account_id ON sips(broker_account_id);
CREATE INDEX idx_sips_instrument_id ON sips(instrument_id);
