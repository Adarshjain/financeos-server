package com.financeos.gmail.internal;

public record GmailAttachment(
        String attachmentId,
        String filename,
        String mimeType,
        byte[] content
) {}

