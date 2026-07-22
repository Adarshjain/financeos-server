package com.financeos.api.transactionlink.dto;

import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.LinkType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionLinkResponse(
        UUID id,
        LinkType type,
        String note,
        LinkOrigin createdBy,
        Instant createdAt,
        List<MemberSummary> members
) {}
