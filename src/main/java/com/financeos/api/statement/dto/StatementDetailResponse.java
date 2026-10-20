package com.financeos.api.statement.dto;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StatementDetailResponse(
        UUID id,
        StatementSource source,
        @Nullable String sourceRef,
        @Nullable String statementType,
        @Nullable LocalDate periodStart,
        @Nullable LocalDate periodEnd,
        @Nullable BigDecimal openingBalance,
        @Nullable BigDecimal closingBalance,
        @Nullable BigDecimal totalDebits,
        @Nullable BigDecimal totalCredits,
        @Nullable Integer transactionCount,
        @Nullable Integer linesSkipped,
        @Nullable String parseMode,
        @Nullable BigDecimal chainValidationPct,
        @Nullable Boolean checksumOk,
        @Nullable StatementVerdict verdict,
        @Nullable String bankName,
        @Nullable String accountNumberMasked,
        Instant createdAt,
        @Nullable StatementCardDetailsResponse cardDetails,
        List<StatementLineResponse> lines,
        /** The account's balance from its own transactions at the end of periodEnd, in the sign of closingBalance. */
        @Nullable BigDecimal ledgerBalanceAtClose,
        @Nullable Boolean matchesLedger,
        /** Credit-card figures still empty: PERIOD_END, DUE_DATE, TOTAL_DUE, MINIMUM_DUE; empty when complete. */
        List<String> missingDetails
) {
    public StatementDetailResponse(UUID id, StatementSource source, @Nullable String sourceRef,
                                   @Nullable String statementType, @Nullable LocalDate periodStart,
                                   @Nullable LocalDate periodEnd, @Nullable BigDecimal openingBalance,
                                   @Nullable BigDecimal closingBalance, @Nullable BigDecimal totalDebits,
                                   @Nullable BigDecimal totalCredits, @Nullable Integer transactionCount,
                                   @Nullable Integer linesSkipped, @Nullable String parseMode,
                                   @Nullable BigDecimal chainValidationPct, @Nullable Boolean checksumOk,
                                   @Nullable StatementVerdict verdict, @Nullable String bankName,
                                   @Nullable String accountNumberMasked, Instant createdAt,
                                   @Nullable StatementCardDetailsResponse cardDetails,
                                   List<StatementLineResponse> lines) {
        this(id, source, sourceRef, statementType, periodStart, periodEnd, openingBalance, closingBalance,
                totalDebits, totalCredits, transactionCount, linesSkipped, parseMode, chainValidationPct,
                checksumOk, verdict, bankName, accountNumberMasked, createdAt, cardDetails, lines, null, null, List.of());
    }

    public static StatementDetailResponse from(Statement statement, StatementCardDetailsResponse cardDetails,
                                               List<StatementLineResponse> lines,
                                               @Nullable BigDecimal ledgerBalanceAtClose,
                                               @Nullable Boolean matchesLedger) {
        return new StatementDetailResponse(
                statement.getId(),
                statement.getSource(),
                statement.getSourceRef(),
                statement.getStatementType(),
                statement.getPeriodStart(),
                statement.getPeriodEnd(),
                statement.getOpeningBalance(),
                statement.getClosingBalance(),
                statement.getTotalDebits(),
                statement.getTotalCredits(),
                statement.getTransactionCount(),
                statement.getLinesSkipped(),
                statement.getParseMode(),
                statement.getChainValidationPct(),
                statement.getChecksumOk(),
                statement.getVerdict(),
                statement.getBankName(),
                statement.getAccountNumberMasked(),
                statement.getCreatedAt(),
                cardDetails,
                lines,
                ledgerBalanceAtClose,
                matchesLedger,
                com.financeos.domain.statement.MissingDetails.of(statement)
        );
    }
}
