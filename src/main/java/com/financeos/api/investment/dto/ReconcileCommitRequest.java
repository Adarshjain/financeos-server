package com.financeos.api.investment.dto;

import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import com.financeos.domain.investment.reconcile.Broker;
import com.financeos.api.investment.dto.ImportCommitRequest.CreateInstrumentDto;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReconcileCommitRequest(
        @NotNull Broker broker,
        @NotNull UUID brokerAccountId,
        @NotNull List<CommitExecutionDto> executions,
        List<CommitClassificationDto> classifications
) {
    public record CommitExecutionDto(
            int rowIndex,
            LocalDate tradeDate,
            InvestmentTransactionType type,
            SettlementType settlementType,
            String symbol,
            String isin,
            String exchange,
            BigDecimal quantity,
            BigDecimal price,
            ItemizedChargesDto charges,
            String externalRef,
            UUID instrumentId,
            CreateInstrumentDto newInstrument,
            boolean skip
    ) {}

    public record CommitClassificationDto(
            String isin,
            String symbol,
            LocalDate tradeDate,
            BigDecimal intradayQty,
            BigDecimal intradayBuyValue,
            BigDecimal intradaySellValue
    ) {}
}
