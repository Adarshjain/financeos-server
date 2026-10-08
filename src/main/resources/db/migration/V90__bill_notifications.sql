-- V90__bill_notifications.sql
-- Credit-card bill payment notifications. The statement IS the bill: the card-details row that
-- already holds the due date and amounts gains the user's manual "paid" mark and the send log
-- (last kind + date), so no bills or notifications table is needed. Per-user notification
-- preferences and the Web Push device subscriptions live in one new row per user.
--
-- Idempotent: every DDL swallows "already exists" (ORA-00955 object, ORA-01430 column).

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
    -- Manual "mark as paid" (amount NULL = paid in full) and the send log.
    run_ddl_swallowing('ALTER TABLE statement_credit_card_details ADD paid_marked_on DATE', -1430);
    run_ddl_swallowing('ALTER TABLE statement_credit_card_details ADD paid_marked_amount NUMBER(19, 4)', -1430);
    run_ddl_swallowing('ALTER TABLE statement_credit_card_details ADD last_notified_kind VARCHAR2(20)', -1430);
    run_ddl_swallowing('ALTER TABLE statement_credit_card_details ADD last_notified_on DATE', -1430);

    -- Per-card mute (covers "I have autopay on this card").
    run_ddl_swallowing('ALTER TABLE accounts ADD notifications_muted NUMBER(1) DEFAULT 0 NOT NULL', -1430);

    -- One row per user: preferences + the device push subscriptions as a JSON array.
    -- kinds_json: {"STATEMENT_RECEIVED":true,...}; push_subscriptions: [{"endpoint":..,"p256dh":..,"auth":..}]
    run_ddl_swallowing('CREATE TABLE user_notification_settings (
        user_id            VARCHAR2(36) PRIMARY KEY,
        push_enabled       NUMBER(1) DEFAULT 1 NOT NULL,
        send_hour          NUMBER(2) DEFAULT 9 NOT NULL,
        reminder_offsets   VARCHAR2(40) DEFAULT ''7,3,1,0'' NOT NULL,
        kinds_json         CLOB,
        push_subscriptions CLOB,
        created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_uns_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT chk_uns_send_hour CHECK (send_hour BETWEEN 0 AND 23)
    )', -955);
END;
/
