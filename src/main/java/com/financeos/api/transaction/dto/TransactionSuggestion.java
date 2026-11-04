package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.autolink.SuggestionKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * An open link suggestion as one transaction row sees it. {@code candidates} are the counterpart(s) to this row:
 * for a debit subject the credits, for the credit side of a transfer or card payment the debit that proposed
 * it (accept then with this row's own id as candidateId), for a LENDING suggestion the person's lending entry.
 * Candidate {@code amount} is signed like a transaction (debits negative); a lending's is the entry amount.
 */
public record TransactionSuggestion(UUID id, SuggestionKind kind, String label, List<Candidate> candidates) {

    /** Exactly one of transactionId / lendingId is set. */
    public record Candidate(@Nullable UUID transactionId, @Nullable UUID lendingId, LocalDate date,
                            @Nullable String description, @Nullable String accountName, BigDecimal amount) {
    }
}
