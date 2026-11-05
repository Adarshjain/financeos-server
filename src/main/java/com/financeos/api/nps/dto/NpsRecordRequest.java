package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsTier;
import org.springframework.lang.Nullable;

import java.util.UUID;

/** Records a payment as a contribution; {@code accountId} may be omitted with one active own account, {@code tier} defaults to I. */
public record NpsRecordRequest(@Nullable UUID accountId, @Nullable NpsTier tier) {
}
