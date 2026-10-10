package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.TaxClass;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.dto.RealizedLot;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Asset class and tax class on the positions and realized_lots datasources; the slab term. */
class InvestmentDatasourceClassFieldsTest {

    private final InvestmentService investmentService = mock(InvestmentService.class);

    private static FieldDef field(List<FieldDef> fields, String name) {
        return fields.stream().filter(f -> name.equals(f.name())).findFirst().orElseThrow();
    }

    private static void assertClassDimension(FieldDef f, List<String> values) {
        assertEquals(FieldType.ENUM, f.type());
        assertEquals(FieldRole.DIMENSION, f.role());
        assertEquals(values, f.values());
        assertEquals(List.of(ReportType.CHART, ReportType.TABLE), f.allowedInReports());
        assertTrue(f.canFilter());
        assertEquals(values, List.copyOf(f.valueLabels().keySet()));
    }

    @Test
    void positionsOffersAssetAndTaxClassAsLabelledFilterableDimensions() {
        List<FieldDef> fields = new PositionsDatasource(investmentService).fields();

        FieldDef asset = field(fields, "assetClass");
        assertClassDimension(asset, List.of("EQUITY", "DEBT", "HYBRID", "GOLD", "INTERNATIONAL", "OTHER"));
        assertEquals("International", asset.valueLabels().get("INTERNATIONAL"));
        assertEquals("Equity", asset.displayValue("EQUITY"));

        FieldDef tax = field(fields, "taxClass");
        assertClassDimension(tax, List.of("EQUITY_ORIENTED", "SPECIFIED_DEBT", "OTHER"));
        assertEquals("Equity-oriented", tax.valueLabels().get("EQUITY_ORIENTED"));
        assertEquals("Specified debt", tax.valueLabels().get("SPECIFIED_DEBT"));
    }

    private static PositionDto position(AssetClass assetClass, TaxClass taxClass) {
        PositionDto.InstrumentInfoDto inst = new PositionDto.InstrumentInfoDto(UUID.randomUUID(), InstrumentType.etf,
                "Gold BeES", "GOLDBEES", null, null, null, null);
        PositionDto base = new PositionDto(UUID.randomUUID(), UUID.randomUUID(), "Zerodha", "KITE", inst,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null, null, null, null, null, null);
        return new PositionDto(base.holdingId(), base.brokerAccountId(), base.brokerName(), base.provider(), base.instrument(),
                base.quantity(), base.avgCost(), base.invested(), base.lastPrice(), base.lastPriceAsOf(), base.lastPriceSource(),
                base.currentValue(), base.unrealizedGainLoss(), base.unrealizedGainLossPercent(), base.realizedGainLoss(),
                base.intradayRealized(), base.dividends(), base.xirr(), base.absoluteReturnPercent(), base.totalCharges(),
                base.notes(), null, null, null, null, null, null, null, null, null, null, assetClass, taxClass,
                null, null, null, null);
    }

    @Test
    void positionRowsCarryTheClasses() {
        when(investmentService.getAllPositions()).thenReturn(List.of(
                position(AssetClass.GOLD, TaxClass.OTHER), position(null, null)));

        List<Map<String, Object>> rows = new PositionsDatasource(investmentService).rows();

        assertEquals("GOLD", rows.get(0).get("assetClass"));
        assertEquals("OTHER", rows.get(0).get("taxClass"));
        assertEquals(null, rows.get(1).get("assetClass"));
        assertEquals(null, rows.get(1).get("taxClass"));
    }

    @Test
    void theLegacyPositionConstructorsLeaveClassesAndDayChangeEmpty() {
        PositionDto legacy = position(AssetClass.GOLD, TaxClass.OTHER);
        PositionDto shortForm = new PositionDto(legacy.holdingId(), legacy.brokerAccountId(), legacy.brokerName(),
                legacy.provider(), legacy.instrument(), legacy.quantity(), legacy.avgCost(), legacy.invested(),
                legacy.lastPrice(), legacy.lastPriceAsOf(), legacy.lastPriceSource(), legacy.currentValue(),
                legacy.unrealizedGainLoss(), legacy.unrealizedGainLossPercent(), legacy.realizedGainLoss(),
                legacy.intradayRealized(), legacy.dividends(), legacy.xirr(), legacy.absoluteReturnPercent(),
                legacy.totalCharges(), legacy.notes());
        assertEquals(null, shortForm.assetClass());
        assertEquals(null, shortForm.dayChange());
    }

    // ------------------------------------------------------------------ realized_lots

    @Test
    void realizedLotsTermIncludesSlabWithLabels() {
        FieldDef term = field(new RealizedLotsDatasource(investmentService).fields(), "term");
        assertEquals(List.of("short", "long", "slab"), term.values());
        assertEquals("Short term", term.displayValue("short"));
        assertEquals("Long term", term.displayValue("long"));
        assertEquals("Slab rate", term.displayValue("slab"));
    }

    @Test
    void realizedLotsOffersClassesAndAGrandfatheredFilter() {
        List<FieldDef> fields = new RealizedLotsDatasource(investmentService).fields();
        assertClassDimension(field(fields, "assetClass"), List.of("EQUITY", "DEBT", "HYBRID", "GOLD", "INTERNATIONAL", "OTHER"));
        assertClassDimension(field(fields, "taxClass"), List.of("EQUITY_ORIENTED", "SPECIFIED_DEBT", "OTHER"));
        FieldDef grandfathered = field(fields, "grandfathered");
        assertEquals(FieldType.BOOLEAN, grandfathered.type());
        assertEquals(FieldRole.FILTER, grandfathered.role());
    }

    @Test
    void realizedLotRowsCarryTheClassesAndGrandfathering() {
        when(investmentService.getAllRealizedLots()).thenReturn(List.of(new RealizedLot(UUID.randomUUID(), UUID.randomUUID(),
                "Zerodha", UUID.randomUUID(), "TATA MOTORS", InstrumentType.stock, LocalDate.of(2017, 6, 1),
                LocalDate.of(2025, 6, 1), BigDecimal.TEN, new BigDecimal("1000"), new BigDecimal("2000"),
                new BigDecimal("1000"), 2922, "long", AssetClass.EQUITY, TaxClass.EQUITY_ORIENTED, true)));

        Map<String, Object> row = new RealizedLotsDatasource(investmentService).rows().get(0);

        assertEquals("long", row.get("term"));
        assertEquals("EQUITY", row.get("assetClass"));
        assertEquals("EQUITY_ORIENTED", row.get("taxClass"));
        assertEquals(true, row.get("grandfathered"));
    }
}
