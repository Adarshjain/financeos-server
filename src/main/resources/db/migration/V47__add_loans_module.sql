-- V47__add_loans_module.sql
-- Migration for Loans and Lendings module

CREATE TABLE loans (
    id                   VARCHAR2(36) PRIMARY KEY,
    user_id              VARCHAR2(36) NOT NULL,
    name                 VARCHAR2(200) NOT NULL,
    loan_type            VARCHAR2(30) NOT NULL,
    lender               VARCHAR2(200) NOT NULL,
    loan_account_number  VARCHAR2(100),
    payment_account_id   VARCHAR2(36),
    principal            NUMBER(19,4) NOT NULL,
    annual_rate_pct      NUMBER(9,4) NOT NULL,
    rate_type            VARCHAR2(30) NOT NULL,
    tenure_months        NUMBER(10) NOT NULL,
    start_date           DATE NOT NULL,
    first_emi_date       DATE NOT NULL,
    emi_amount           NUMBER(19,4) NOT NULL,
    status               VARCHAR2(30) DEFAULT 'active' NOT NULL,
    notes                VARCHAR2(1000),
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_loans_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_loans_payment_account FOREIGN KEY (payment_account_id) REFERENCES accounts(id) ON DELETE SET NULL,
    CONSTRAINT chk_loan_type CHECK (loan_type IN ('home', 'car', 'personal', 'education', 'gold', 'two_wheeler', 'consumer_durable', 'other')),
    CONSTRAINT chk_rate_type CHECK (rate_type IN ('fixed', 'floating')),
    CONSTRAINT chk_loan_status CHECK (status IN ('active', 'closed', 'foreclosed'))
);

CREATE INDEX idx_loans_user ON loans(user_id);
CREATE INDEX idx_loans_payment_account ON loans(payment_account_id);

CREATE TABLE loan_events (
    id                   VARCHAR2(36) PRIMARY KEY,
    user_id              VARCHAR2(36) NOT NULL,
    loan_id              VARCHAR2(36) NOT NULL,
    event_type           VARCHAR2(30) NOT NULL,
    effective_date       DATE NOT NULL,
    new_annual_rate_pct  NUMBER(9,4),
    amount               NUMBER(19,4),
    adjustment_mode      VARCHAR2(30),
    new_emi_override     NUMBER(19,4),
    transaction_id       VARCHAR2(36),
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_loan_events_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_loan_events_loan FOREIGN KEY (loan_id) REFERENCES loans(id) ON DELETE CASCADE,
    CONSTRAINT fk_loan_events_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL,
    CONSTRAINT chk_loan_event_type CHECK (event_type IN ('rate_change', 'prepayment', 'foreclosure')),
    CONSTRAINT chk_loan_event_adj_mode CHECK (adjustment_mode IS NULL OR adjustment_mode IN ('reduce_emi', 'reduce_tenure'))
);

CREATE INDEX idx_loan_events_user ON loan_events(user_id);
CREATE INDEX idx_loan_events_loan ON loan_events(loan_id);
CREATE UNIQUE INDEX idx_loan_events_txn_uniq ON loan_events(transaction_id);

CREATE TABLE loan_payments (
    id               VARCHAR2(36) PRIMARY KEY,
    user_id          VARCHAR2(36) NOT NULL,
    loan_id          VARCHAR2(36) NOT NULL,
    installment_seq  NUMBER(10) NOT NULL,
    payment_date     DATE NOT NULL,
    amount           NUMBER(19,4) NOT NULL,
    transaction_id   VARCHAR2(36),
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_loan_payments_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_loan_payments_loan FOREIGN KEY (loan_id) REFERENCES loans(id) ON DELETE CASCADE,
    CONSTRAINT fk_loan_payments_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL,
    CONSTRAINT uk_loan_payments_seq UNIQUE (loan_id, installment_seq)
);

CREATE INDEX idx_loan_payments_user ON loan_payments(user_id);
CREATE INDEX idx_loan_payments_loan ON loan_payments(loan_id);
CREATE UNIQUE INDEX idx_loan_payments_txn_uniq ON loan_payments(transaction_id);

CREATE TABLE loan_charges (
    id               VARCHAR2(36) PRIMARY KEY,
    user_id          VARCHAR2(36) NOT NULL,
    loan_id          VARCHAR2(36) NOT NULL,
    charge_type      VARCHAR2(30) NOT NULL,
    amount           NUMBER(19,4) NOT NULL,
    charge_date      DATE NOT NULL,
    transaction_id   VARCHAR2(36),
    notes            VARCHAR2(500),
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_loan_charges_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_loan_charges_loan FOREIGN KEY (loan_id) REFERENCES loans(id) ON DELETE CASCADE,
    CONSTRAINT fk_loan_charges_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL,
    CONSTRAINT chk_loan_charge_type CHECK (charge_type IN ('processing_fee', 'insurance_premium', 'foreclosure_charge', 'bounce_charge', 'late_fee', 'legal_valuation', 'other'))
);

CREATE INDEX idx_loan_charges_user ON loan_charges(user_id);
CREATE INDEX idx_loan_charges_loan ON loan_charges(loan_id);
CREATE UNIQUE INDEX idx_loan_charges_txn_uniq ON loan_charges(transaction_id);

CREATE TABLE counterparties (
    id           VARCHAR2(36) PRIMARY KEY,
    user_id      VARCHAR2(36) NOT NULL,
    name         VARCHAR2(200) NOT NULL,
    notes        VARCHAR2(1000),
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_counterparties_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_counterparties_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_counterparties_user ON counterparties(user_id);

CREATE TABLE lendings (
    id                    VARCHAR2(36) PRIMARY KEY,
    user_id               VARCHAR2(36) NOT NULL,
    counterparty_id       VARCHAR2(36) NOT NULL,
    direction             VARCHAR2(30) NOT NULL,
    amount                NUMBER(19,4) NOT NULL,
    lend_date             DATE NOT NULL,
    expected_return_date  DATE,
    status                VARCHAR2(30) DEFAULT 'outstanding' NOT NULL,
    transaction_id        VARCHAR2(36),
    notes                 VARCHAR2(1000),
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_lendings_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_lendings_cp FOREIGN KEY (counterparty_id) REFERENCES counterparties(id) ON DELETE CASCADE,
    CONSTRAINT fk_lendings_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL,
    CONSTRAINT chk_lending_dir CHECK (direction IN ('lent', 'borrowed')),
    CONSTRAINT chk_lending_status CHECK (status IN ('outstanding', 'partially_repaid', 'settled', 'written_off'))
);

CREATE INDEX idx_lendings_user ON lendings(user_id);
CREATE INDEX idx_lendings_cp ON lendings(counterparty_id);
CREATE UNIQUE INDEX idx_lendings_txn_uniq ON lendings(transaction_id);

CREATE TABLE lending_repayments (
    id              VARCHAR2(36) PRIMARY KEY,
    user_id         VARCHAR2(36) NOT NULL,
    lending_id      VARCHAR2(36) NOT NULL,
    amount          NUMBER(19,4) NOT NULL,
    repayment_date  DATE NOT NULL,
    transaction_id  VARCHAR2(36),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_lending_rep_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_lending_rep_lending FOREIGN KEY (lending_id) REFERENCES lendings(id) ON DELETE CASCADE,
    CONSTRAINT fk_lending_rep_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL
);

CREATE INDEX idx_lending_rep_user ON lending_repayments(user_id);
CREATE INDEX idx_lending_rep_lending ON lending_repayments(lending_id);
CREATE UNIQUE INDEX idx_lending_rep_txn_uniq ON lending_repayments(transaction_id);
