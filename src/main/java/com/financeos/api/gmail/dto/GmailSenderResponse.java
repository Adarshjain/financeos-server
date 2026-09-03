package com.financeos.api.gmail.dto;

import com.financeos.gmail.ingest.GmailSender;

import org.springframework.lang.Nullable;

import java.util.UUID;

public record GmailSenderResponse(
    UUID id,
    @Nullable String name,
    String senderAddress,
    boolean enabled
) {
    public static GmailSenderResponse from(GmailSender sender) {
        return new GmailSenderResponse(
            sender.getId(),
            sender.getName(),
            sender.getSenderAddress(),
            sender.getEnabled()
        );
    }
}

