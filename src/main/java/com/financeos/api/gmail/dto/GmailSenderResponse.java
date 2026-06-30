package com.financeos.api.gmail.dto;

import com.financeos.gmail.ingest.GmailSender;
import java.util.UUID;

public record GmailSenderResponse(
    UUID id,
    String name,
    String senderAddress,
    boolean enabled,
    UUID accountId,
    String accountName
) {
    public static GmailSenderResponse from(GmailSender sender) {
        return new GmailSenderResponse(
            sender.getId(),
            sender.getName(),
            sender.getSenderAddress(),
            sender.getEnabled(),
            sender.getAccount() != null ? sender.getAccount().getId() : null,
            sender.getAccount() != null ? sender.getAccount().getName() : null
        );
    }
}

