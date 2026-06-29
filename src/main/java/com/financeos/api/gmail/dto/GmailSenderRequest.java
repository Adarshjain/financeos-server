package com.financeos.api.gmail.dto;

import com.financeos.gmail.ingest.SenderPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GmailSenderRequest(
    @NotBlank(message = "Name is required") String name,
    @NotBlank(message = "Sender address is required") @Email(message = "Invalid email format") String senderAddress,
    UUID accountId,
    @NotNull(message = "Purpose is required") SenderPurpose purpose,
    String attachmentPattern,
    String statementFormat,
    Boolean enabled
) {}
