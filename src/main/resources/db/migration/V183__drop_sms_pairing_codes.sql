-- V183__drop_sms_pairing_codes.sql
-- The phone now signs in with the account's email and password (or Google), so the one-time pairing code is gone.
--
-- Idempotent: the table is dropped only when it exists, so a second run (or a schema without it) does nothing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'SMS_PAIRING_CODES';
    IF n > 0 THEN
        EXECUTE IMMEDIATE 'DROP TABLE sms_pairing_codes PURGE';
    END IF;
END;
/
