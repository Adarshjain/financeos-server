package com.financeos.domain.transaction.autolink;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The few columns a linker needs from one transaction that is unlinked and owned by no loan, lending,
 * dividend or EPF row. {@code merchant} is the raw description text; linkers normalise it themselves.
 */
public record AutoLinkCandidate(UUID id, UUID accountId, AccountType accountType, LocalDate date, BigDecimal amount,
                                TransactionType type, String merchant) {
}
