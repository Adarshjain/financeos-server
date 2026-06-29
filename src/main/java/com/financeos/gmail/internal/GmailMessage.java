package com.financeos.gmail.internal;

import java.time.Instant;
import java.util.List;

public record GmailMessage(
        String messageId,
        Instant internalDate,
        String from,
        String subject,
        String snippet,
        String bodyText,
        String bodyHtml,
        List<GmailAttachment> attachments
) {
    /**
     * Expose HTML-stripped text by fallback: bodyText -> stripped bodyHtml -> snippet.
     */
    public String getStrippedText() {
        if (bodyText != null && !bodyText.trim().isEmpty()) {
            return bodyText;
        }
        if (bodyHtml != null && !bodyHtml.trim().isEmpty()) {
            // Simple HTML stripping using regex
            String stripped = bodyHtml.replaceAll("<[^>]*>", " ");
            // Decode common HTML entities
            stripped = stripped.replaceAll("&nbsp;", " ")
                               .replaceAll("&lt;", "<")
                               .replaceAll("&gt;", ">")
                               .replaceAll("&amp;", "&");
            // Collapse multiple whitespace
            stripped = stripped.replaceAll("\\s+", " ").trim();
            return stripped;
        }
        return snippet != null ? snippet : "";
    }
}
