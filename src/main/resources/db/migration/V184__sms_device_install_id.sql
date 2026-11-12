-- V184__sms_device_install_id.sql
-- A phone sends a stable id for its app install when it signs in, so signing in again reuses its device row instead of adding a second one.
--
-- Idempotent: the column and the index are each added only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'SMS_DEVICES' AND column_name = 'INSTALL_ID';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE sms_devices ADD (install_id VARCHAR2(64))';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SMS_DEVICES_INSTALL';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_sms_devices_install ON sms_devices (user_id, install_id)';
    END IF;
END;
/
