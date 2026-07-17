package com.financeos.api.statement.dto;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StatementSummaryResponse(
        UUID id,
        StatementSource source,
        String sourceRef,
        String statementType,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        Integer transactionCount,
        Integer linesSkipped,
        String parseMode,
        BigDecimal chainValidationPct,
        Boolean checksumOk,
        StatementVerdict verdict,
        String bankName,
        String accountNumberMasked,
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
