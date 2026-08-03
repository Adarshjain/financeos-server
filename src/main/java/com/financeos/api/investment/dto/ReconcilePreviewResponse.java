package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReconcilePreviewResponse(
        List<ReconciledExecutionDto> executions,
        List<DerivedHoldingDto> derivedHoldings,
        RealizedSummaryDto realizedSummary,
        List<ReconcileWarningDto> warnings,
        SummaryStatsDto summaryStats,
        List<TradeSettlementClassificationDto> classifications
) {
    public record TradeSettlementClassificationDto(
            String isin,
            String symbol,
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
            String symbol,
            String isin,
            String exchange,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal totalValue,
            ItemizedChargesDto charges,
            String externalRef,
            MatchedInstrumentDto matchedInstrument,
            boolean isDuplicate,
            String note
    ) {}

    public record MatchedInstrumentDto(
            UUID id,
            InstrumentType type,
            String name,
            String symbol,
            String exchange,
            String isin
    ) {}

    public record DerivedHoldingDto(
            UUID instrumentId,
            String symbol,
            String isin,
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
            String isin,
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
