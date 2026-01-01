package com.financeos.gmail.internal;

import java.time.Instant;
import java.util.List;

public record GmailMessage(
        String messageId,
        Instant internalDate,
        String from,
        String subject,
        List<GmailAttachment> attachments
) {}

