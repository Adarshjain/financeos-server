package com.financeos.api.gmail.dto;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/** A mail's content, fetched live from Gmail for "View email". Never stored. */
public record GmailMessageContentResponse(
        String subject,
        String from,
        String to,
        @Nullable Instant date,
        /** Sanitized HTML (no images, scripts or external loads); null when the mail has no HTML body. */
        @Nullable String html,
        /** Plain text body; set only when there is no usable HTML. */
        @Nullable String text,
        List<GmailMessageContentAttachment> attachments,
        /** The body is too big or too deeply nested to show and has no text part: the client points to Gmail. */
        boolean tooLarge
) {
    /** An attachment's name only; the file itself is never served. */
    public record GmailMessageContentAttachment(String filename, String mimeType, long size) {
    }
}
