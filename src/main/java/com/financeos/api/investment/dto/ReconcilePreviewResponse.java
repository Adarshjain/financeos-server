package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReconcilePreviewResponse(
        List<ReconciledExecutionDto> executions,
        List<DerivedHoldingDto> derivedHoldings,
        @Nullable RealizedSummaryDto realizedSummary,
        List<ReconcileWarningDto> warnings,
        SummaryStatsDto summaryStats,
        List<TradeSettlementClassificationDto> classifications,
        List<FnoTradePreviewDto> fnoTrades
) {
    public record TradeSettlementClassificationDto(
            @Nullable String isin,
            @Nullable String symbol,
            LocalDate tradeDate,
            BigDecimal intradayQty,
            BigDecimal intradayBuyValue,
            BigDecimal intradaySellValue
    ) {}
    public record ReconciledExecutionDto(
            int rowIndex,
            LocalDate tradeDate,
            InvestmentTransactionType type,
            SettlementType settlementType,
            @Nullable String symbol,
            @Nullable String isin,
            @Nullable String exchange,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal totalValue,
            ItemizedChargesDto charges,
            @Nullable String externalRef,
            @Nullable MatchedInstrumentDto matchedInstrument,
            boolean isDuplicate,
            @Nullable String note
    ) {}

    public record MatchedInstrumentDto(
            UUID id,
            InstrumentType type,
            String name,
            @Nullable String symbol,
            @Nullable String exchange,
            @Nullable String isin
    ) {}

    public record DerivedHoldingDto(
            @Nullable UUID instrumentId,
            String symbol,
            @Nullable String isin,
            String name,
            BigDecimal quantity,
            BigDecimal avgCost,
            BigDecimal costValue
    ) {}

    public record RealizedSummaryDto(
            BigDecimal deliveryRealized,
            BigDecimal intradayRealized,
            BigDecimal totalCharges,
            BigDecimal classifierDeliveryRealized,
            BigDecimal classifierIntradayRealized,
            BigDecimal deliveryDiff,
            BigDecimal intradayDiff
    ) {}

    public record ReconcileWarningDto(
            String type, // DATA_GAP, BUYBACK_EXIT, UNRESOLVED_INSTRUMENT
            String severity, // WARNING, INFO
            @Nullable String isin,
            String symbol,
            String message
    ) {}

    public record SummaryStatsDto(
            int totalExecutions,
            int deliveryExecutions,
            int intradayExecutions,
            int matchedInstruments,
            int unresolvedInstruments,
            int duplicates,
            int warningsCount
    ) {}
}
