package com.financeos.api.inbox.dto;

import org.springframework.lang.Nullable;

/**
 * One button on an inbox row. {@code type} is one of
 * {@code mark_paid | confirm_payment | set_details | snooze | dismiss | open | reconnect | upload | review | approve_suggested | retry};
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
