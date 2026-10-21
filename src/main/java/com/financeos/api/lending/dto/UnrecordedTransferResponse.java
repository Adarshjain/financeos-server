package com.financeos.api.lending.dto;

import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.domain.lending.LendingDirection;

import java.util.UUID;

/** A bank transfer that names a person but has no lending entry; direction is the settlement it would record. */
public record UnrecordedTransferResponse(
        UUID counterpartyId,
        String counterpartyName,
        TransactionResponse transaction,
        LendingDirection direction
) {
}
