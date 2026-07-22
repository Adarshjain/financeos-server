package com.financeos.api.transactionlink.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MemberRef(
        @NotNull UUID transactionId,
        boolean isAnchor
) {}
