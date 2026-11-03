package com.financeos.api.accountdetect.dto;

import com.financeos.domain.account.card.CardholderRelationship;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** "This belongs to that account": the account, what the number is on it, and (ADDON) who holds it. */
public record BelongsRequest(
        @NotNull UUID accountId,
        @NotNull BelongsAs as,
        /** Required for ADDON; ignored otherwise. */
        CardholderRelationship relationship,
        /** Optional, ADDON only. */
        @Size(max = 200) String holderName) {}
