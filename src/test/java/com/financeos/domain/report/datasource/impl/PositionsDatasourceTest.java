package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class PositionsDatasourceTest {

    private InvestmentService investmentService;
    private PositionsDatasource datasource;

    @BeforeEach
    void setUp() {
        investmentService = mock(InvestmentService.class);
        datasource = new PositionsDatasource(investmentService);
    }

    @Test
    void catalogShapeAndName() {
        assertEquals("positions", datasource.name());
        assertEquals("Positions", datasource.label());
        assertNotNull(datasource.fields());
        assertTrue(datasource.fields().stream().anyMatch(f -> "invested".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "isOpen".equals(f.name())));
    }

    @Test
    void rowsMappingIncludesOpenAndClosedHoldings() {
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();

        PositionDto.InstrumentInfoDto inst1 = new PositionDto.InstrumentInfoDto(
                UUID.randomUUID(), InstrumentType.stock, "TATA MOTORS", "TATAMOTORS", "INE155A01022", null, null, null
        );

        // Open holding with currentValue
        PositionDto openPos = new PositionDto(
                h1, UUID.randomUUID(), "Zerodha", "KITE", inst1,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("1000"),
                new BigDecimal("120"), null, null, new BigDecimal("1200"),
                new BigDecimal("200"), new BigDecimal("20"), new BigDecimal("0"),
                new BigDecimal("0"), new BigDecimal("50"), 15.5,
                new BigDecimal("20"), new BigDecimal("15"), "Notes"
        );

        // Closed holding with null currentValue
        PositionDto closedPos = new PositionDto(
                h2, UUID.randomUUID(), "Zerodha", "KITE", inst1,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300"),
                BigDecimal.ZERO, BigDecimal.ZERO, null,
                BigDecimal.ZERO, new BigDecimal("5"), "Closed"
        );

        when(investmentService.getAllPositions()).thenReturn(List.of(openPos, closedPos));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(2, rows.size());

        // Assert open position row
        Map<String, Object> r1 = rows.get(0);
        assertEquals(h1.toString(), r1.get("id"));
        assertEquals("Zerodha", r1.get("broker"));
        assertEquals("TATA MOTORS", r1.get("instrument"));
        assertEquals("stock", r1.get("instrumentType"));
        assertEquals(new BigDecimal("1200"), r1.get("currentValue"));
        assertEquals(Boolean.TRUE, r1.get("isOpen"));

        // Assert closed position row
        Map<String, Object> r2 = rows.get(1);
        assertEquals(h2.toString(), r2.get("id"));
        assertNull(r2.get("currentValue"));
        assertEquals(new BigDecimal("300"), r2.get("realizedGainLoss"));
        assertEquals(Boolean.FALSE, r2.get("isOpen"));
    }
}
