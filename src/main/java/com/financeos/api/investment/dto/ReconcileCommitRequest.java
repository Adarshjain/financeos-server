package com.financeos.api.investment.dto;

import com.financeos.api.investment.dto.ImportCommitRequest.CreateInstrumentDto;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import com.financeos.domain.investment.reconcile.Broker;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReconcileCommitRequest(
        @NotNull Broker broker,
        @NotNull UUID brokerAccountId,
        @NotNull List<CommitExecutionDto> executions,
        List<CommitClassificationDto> classifications,
        List<CommitFnoTradeDto> fnoTrades
) {
    public record CommitExecutionDto(
            int rowIndex,
            @Nullable LocalDate tradeDate,
            InvestmentTransactionType type,
            @Nullable SettlementType settlementType,
            @Nullable String symbol,
            @Nullable String isin,
            @Nullable String exchange,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal price,
            @Nullable ItemizedChargesDto charges,
            @Nullable String externalRef,
            @Nullable UUID instrumentId,
            @Nullable CreateInstrumentDto newInstrument,
            boolean skip
    ) {}

    public record CommitClassificationDto(
            @Nullable String isin,
            @Nullable String symbol,
            LocalDate tradeDate,
            BigDecimal intradayQty,
            BigDecimal intradayBuyValue,
            BigDecimal intradaySellValue
    ) {}
}
