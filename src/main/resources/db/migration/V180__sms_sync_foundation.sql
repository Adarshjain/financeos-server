-- V180__sms_sync_foundation.sql
-- SMS sync, phase 1: paired phones (devices), single-use pairing codes, and the per-user "lines" a phone reports
-- (a line = sender header + ending digits, or sender + message family for numberless wallets). Only the SHA-256 hash
-- of a pairing code or device token is stored. Lines start OFF; the user turns them on per account. No message text yet.
-- Everything cascades with the user; the account links are cleared when an account goes.
--
-- Idempotent: tables, indexes created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'SMS_DEVICES';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE sms_devices (
            id                VARCHAR2(36) NOT NULL,
            user_id           VARCHAR2(36) NOT NULL,
            name              VARCHAR2(80) NOT NULL,
            token_hash        VARCHAR2(64) NOT NULL,
            paired_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            last_seen_at      TIMESTAMP(6) WITH TIME ZONE,
            last_summary_at   TIMESTAMP(6) WITH TIME ZONE,
            paused            NUMBER(1) DEFAULT 0 NOT NULL,
            revoked_at        TIMESTAMP(6) WITH TIME ZONE,
            CONSTRAINT pk_sms_devices PRIMARY KEY (id),
            CONSTRAINT uq_sms_devices_token UNIQUE (token_hash),
            CONSTRAINT fk_sms_devices_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_DEVICES_USER';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_devices_user ON sms_devices (user_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'SMS_PAIRING_CODES';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE sms_pairing_codes (
            code_hash   VARCHAR2(64) NOT NULL,
            user_id     VARCHAR2(36) NOT NULL,
            expires_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            used_at     TIMESTAMP(6) WITH TIME ZONE,
            CONSTRAINT pk_sms_pairing_codes PRIMARY KEY (code_hash),
            CONSTRAINT fk_sms_pairing_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_PAIRING_USER';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_pairing_user ON sms_pairing_codes (user_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'SMS_LINES';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE sms_lines (
            id                    VARCHAR2(36) NOT NULL,
            user_id               VARCHAR2(36) NOT NULL,
            sender                VARCHAR2(20) NOT NULL,
            ending                VARCHAR2(4),
            family                VARCHAR2(40),
            provider              VARCHAR2(80) NOT NULL,
            msg_count             NUMBER(10) DEFAULT 0 NOT NULL,
            txn_count             NUMBER(10) DEFAULT 0 NOT NULL,
            first_msg_at          TIMESTAMP(6) WITH TIME ZONE,
            last_msg_at           TIMESTAMP(6) WITH TIME ZONE,
            state                 VARCHAR2(10) NOT NULL,
            account_id            VARCHAR2(36),
            suggested_account_id  VARCHAR2(36),
            sync_from             DATE,
            seen_by_user          NUMBER(1) DEFAULT 0 NOT NULL,
            created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            updated_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
            CONSTRAINT pk_sms_lines PRIMARY KEY (id),
            CONSTRAINT fk_sms_lines_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_sms_lines_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL,
            CONSTRAINT fk_sms_lines_suggested FOREIGN KEY (suggested_account_id) REFERENCES accounts(id) ON DELETE SET NULL,
            CONSTRAINT ck_sms_lines_state CHECK (state IN (''OFF'', ''ON'', ''NOT_MINE''))
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'UK_SMS_LINES_IDENTITY';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX uk_sms_lines_identity ON sms_lines (user_id, sender, NVL(ending, ''-''), NVL(family, ''-''))';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_LINES_ACCOUNT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_lines_account ON sms_lines (account_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_LINES_SUGGESTED';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_lines_suggested ON sms_lines (suggested_account_id)';
    END IF;
END;
/
