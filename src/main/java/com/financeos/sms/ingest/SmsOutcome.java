package com.financeos.sms.ingest;

/** What happened (CREATED, ATTACHED) or, in shadow mode, would happen (SHADOW_*) to a read message. */
public enum SmsOutcome {
    SHADOW_CREATE, SHADOW_ATTACH, CREATED, ATTACHED, DUPLICATE, SKIPPED_BEFORE_START, LINE_OFF
}
