package com.financeos.api.transactionlink.dto;

import com.financeos.domain.transaction.link.LinkType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateTransactionLinkRequest(
        @NotNull LinkType type,
        String note,
        Boolean alignRefundCategories,
        @NotEmpty List<MemberRef> members
) {}
