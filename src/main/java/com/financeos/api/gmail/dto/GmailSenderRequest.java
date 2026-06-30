package com.financeos.api.gmail.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record GmailSenderRequest(
    String name,
    @NotBlank(message = "Sender address is required") String senderAddress,
    Boolean enabled,
    UUID accountId
) {}

