-- V181__sms_messages.sql
-- SMS sync, phase 2: stored messages (body encrypted by the application), what the reader made of them and what would
-- happen to them (shadow outcome). Fingerprint is unique per user. account_id is copied from the line at intake.
-- Also the four skipped counts a phone reports in its summary, kept on the device row.
--
-- Text derived from a message uses CHAR semantics (the application also cuts values to the column length).
--
-- Idempotent: table, columns and indexes created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'SMS_MESSAGES';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE sms_messages (
            id             VARCHAR2(36) NOT NULL,
            user_id        VARCHAR2(36) NOT NULL,
            device_id      VARCHAR2(36),
            line_id        VARCHAR2(36),
            account_id     VARCHAR2(36),
            sender         VARCHAR2(20 CHAR) NOT NULL,
            body           CLOB NOT NULL,
            received_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            sim_slot       NUMBER(3),
            fingerprint    VARCHAR2(64) NOT NULL,
            kind           VARCHAR2(30) NOT NULL,
            read_status    VARCHAR2(20) NOT NULL,
            template_key   VARCHAR2(40 CHAR),
            amount         NUMBER(19,2),
            direction      VARCHAR2(10),
            ending         VARCHAR2(4 CHAR),
            ref_no         VARCHAR2(60 CHAR),
            balance_after  NUMBER(19,2),
            counterparty   VARCHAR2(200 CHAR),
            txn_date       DATE,
            user_decision  VARCHAR2(10),
            outcome        VARCHAR2(30),
            duplicate_of   VARCHAR2(36),
            transaction_id VARCHAR2(36),
            created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            CONSTRAINT pk_sms_messages PRIMARY KEY (id),
            CONSTRAINT uk_sms_messages_fp UNIQUE (user_id, fingerprint),
            CONSTRAINT fk_sms_messages_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_sms_messages_device FOREIGN KEY (device_id) REFERENCES sms_devices(id) ON DELETE SET NULL,
            CONSTRAINT fk_sms_messages_line FOREIGN KEY (line_id) REFERENCES sms_lines(id) ON DELETE SET NULL,
            CONSTRAINT fk_sms_messages_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_MESSAGES_LINE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_messages_line ON sms_messages (user_id, line_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_MESSAGES_STATUS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_messages_status ON sms_messages (user_id, read_status, received_at)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_MESSAGES_ACCOUNT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_messages_account ON sms_messages (user_id, account_id, received_at)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_MESSAGES_LINE_ID';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_messages_line_id ON sms_messages (line_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_MESSAGES_DEVICE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_messages_device ON sms_messages (device_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_MESSAGES_ACCOUNT_ID';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_messages_account_id ON sms_messages (account_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'SMS_DEVICES' AND column_name = 'SKIPPED_PERSONAL';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE sms_devices ADD (skipped_personal NUMBER(10) DEFAULT 0 NOT NULL)';
    END IF;
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'SMS_DEVICES' AND column_name = 'SKIPPED_OTP';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE sms_devices ADD (skipped_otp NUMBER(10) DEFAULT 0 NOT NULL)';
    END IF;
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'SMS_DEVICES' AND column_name = 'SKIPPED_PROMO';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE sms_devices ADD (skipped_promo NUMBER(10) DEFAULT 0 NOT NULL)';
    END IF;
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'SMS_DEVICES' AND column_name = 'SKIPPED_UNKNOWN_SENDER';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE sms_devices ADD (skipped_unknown_sender NUMBER(10) DEFAULT 0 NOT NULL)';
    END IF;
END;
/
