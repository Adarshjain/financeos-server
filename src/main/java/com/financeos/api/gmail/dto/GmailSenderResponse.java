package com.financeos.api.gmail.dto;

import com.financeos.gmail.ingest.GmailSender;
import com.financeos.gmail.ingest.SenderPurpose;
import java.util.UUID;

public record GmailSenderResponse(
    UUID id,
    String name,
    String senderAddress,
    UUID accountId,
    String accountName,
    SenderPurpose purpose,
    String attachmentPattern,
    String statementFormat,
    boolean enabled
) {
    public static GmailSenderResponse from(GmailSender sender) {
        return new GmailSenderResponse(
            sender.getId(),
            sender.getName(),
            sender.getSenderAddress(),
            sender.getAccount() != null ? sender.getAccount().getId() : null,
            sender.getAccount() != null ? sender.getAccount().getName() : null,
            sender.getPurpose(),
            sender.getAttachmentPattern(),
            sender.getStatementFormat(),
            sender.getEnabled()
        );
    }
}
