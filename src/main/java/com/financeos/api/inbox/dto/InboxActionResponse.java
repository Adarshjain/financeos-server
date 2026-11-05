package com.financeos.api.inbox.dto;

import org.springframework.lang.Nullable;

/**
 * One button on an inbox row. {@code type} is one of
 * {@code mark_paid | set_details | snooze | dismiss | open | reconnect | upload | review | approve_suggested | retry | epf_link | no_statement |
 * nps_password | nps_add_self | nps_add_child | nps_ignore | nps_link | nps_dismiss | nps_record | nps_not_nps |
 * nps_setup_not_nps};
 * {@code nps_link} / {@code nps_dismiss} act on {@code refs.npsEntryId} + {@code refs.transactionId} through
 * {@code /api/v1/nps/entries/{id}/transaction | /suggestion/dismiss}; {@code nps_record} / {@code nps_not_nps} act on
 * {@code refs.transactionId} through {@code /api/v1/nps/transactions/{id}/record | /not-nps};
 * {@code nps_add_self} / {@code nps_add_child} / {@code nps_ignore} answer the row's {@code refs.npsPendingId} (the detection key)
 * through {@code /api/v1/nps/gmail-detections/{key}/account | DELETE}; {@code nps_password} posts the typed password to
 * {@code /api/v1/nps/gmail-detections/password}; {@code nps_setup_not_nps} posts to {@code /api/v1/nps/setup/not-nps};
 * {@code no_statement} posts {@code payload.accountId} + {@code payload.date} (the period end) to the statement-skips endpoint;
 * {@code href} is where a navigation action lands, {@code payload} what a mutation action needs.
 */
public record InboxActionResponse(
        String type,
        String label,
        @Nullable String href,
        @Nullable InboxActionPayloadResponse payload
) {
    public static InboxActionResponse navigate(String type, String label, String href) {
        return new InboxActionResponse(type, label, href, null);
    }

    public static InboxActionResponse mutate(String type, String label, @Nullable InboxActionPayloadResponse payload) {
        return new InboxActionResponse(type, label, null, payload);
    }
}
