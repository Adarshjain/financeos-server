package com.financeos.api.transaction.dto;

import java.util.List;
import java.util.UUID;

/** skippedTransactionIds: supplied rows left as they are (edited since, moved off the rule, or not the caller's). */
public record UndoApproveResponse(List<UUID> restoredTransactionIds, List<UUID> skippedTransactionIds) {}
