package com.financeos.api.investment.dto;

import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.domain.investment.dividend.DividendMatchReason;
import com.financeos.domain.investment.dividend.DividendMatchTier;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Unresolved dividends that have at least one plausible bank credit. Each credit appears under at
 * most one dividend (greedy assignment), ranked best first.
 *
 * @param coverageEnd     latest transaction date on any tracked bank account (null = no bank data)
 * @param unresolvedCount unresolved dividends examined (linked / manually resolved rows excluded)
 * @param withCandidates  how many of them have at least one candidate (= {@code items.size()})
 */
public record DividendReconciliationResponse(
        List<DividendReconciliationItem> items,
        @Nullable LocalDate coverageEnd,
        int unresolvedCount,
        int withCandidates
) {
    public record DividendReconciliationItem(
            DividendResponse dividend,
            List<DividendMatchCandidate> candidates
    ) {}

    public record DividendMatchCandidate(
            TransactionResponse transaction,
            DividendMatchTier tier,
            int score,
            List<DividendMatchReason> reasons,
            /** gross − received when the gap looks like tax deducted at source; null otherwise. */
            @Nullable BigDecimal impliedTds,
            /** received − (gross − recorded TDS); positive = more arrived than expected. */
            BigDecimal variance
    ) {}
}
