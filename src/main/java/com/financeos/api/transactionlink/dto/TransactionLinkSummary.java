package com.financeos.api.transactionlink.dto;

import com.financeos.domain.transaction.link.LinkType;

import java.util.UUID;

public record TransactionLinkSummary(
        UUID linkId,
        LinkType type,
        String roleLabel,
        int memberCount
) {}
