package com.financeos.api.statement.dto;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StatementSummaryResponse(
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
        Instant createdAt
) {
    public static StatementSummaryResponse from(Statement statement) {
        return new StatementSummaryResponse(
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
                statement.getCreatedAt()
        );
    }
}
