-- V108__app_lock_devices.sql
-- App lock: one row per enrolled device, holding that device's passkey (WebAuthn public key). The lock
-- state itself lives in the server session; this table is what an unlock is verified against.
-- user_handle is a per-device random value (NOT the user id): synced passkeys are keyed by
-- (relying party, user handle), so a shared handle would make a second device replace the first one's
-- passkey. Binary values are stored as base64url text.
--
-- Idempotent: the DDL swallows ORA-00955 (object already exists) and ORA-01408 (index already exists).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT 0) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE NOT IN (p_err1, p_err2) THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('CREATE TABLE app_lock_devices (
        id                  VARCHAR2(36) PRIMARY KEY,
        user_id             VARCHAR2(36) NOT NULL,
        user_handle         VARCHAR2(64) NOT NULL,
        credential_id       VARCHAR2(1400) NOT NULL,
        public_key_cose     VARCHAR2(2000) NOT NULL,
        signature_count     NUMBER(19) DEFAULT 0 NOT NULL,
        lock_after_seconds  NUMBER(6) DEFAULT 60 NOT NULL,
        label               VARCHAR2(100),
        transports          VARCHAR2(200),
        backup_eligible     NUMBER(1),
        backed_up           NUMBER(1),
        created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
        last_used_at        TIMESTAMP WITH TIME ZONE,
        CONSTRAINT fk_app_lock_devices_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT uq_app_lock_devices_handle UNIQUE (user_handle),
        CONSTRAINT uq_app_lock_devices_cred UNIQUE (credential_id),
        CONSTRAINT ck_app_lock_devices_after CHECK (lock_after_seconds IN (0, 60, 300, 900))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_app_lock_devices_user ON app_lock_devices (user_id)', -955, -1408);
END;
/
