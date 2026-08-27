package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LendingsDatasourceTest {

    private LendingService lendingService;
    private LendingsDatasource datasource;

    @BeforeEach
    void setUp() {
        lendingService = mock(LendingService.class);
        datasource = new LendingsDatasource(lendingService);
    }

    @Test
    void catalogShapeAndName() {
        assertEquals("lendings", datasource.name());
        assertEquals("Lendings", datasource.label());
        assertNotNull(datasource.fields());
        assertTrue(datasource.fields().stream().anyMatch(f -> "entryDate".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "amount".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "signedAmount".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "counterpartyName".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "direction".equals(f.name())));
    }

    @Test
    void rowsMappingWithSignedAmountCalculation() {
        UUID cpId = UUID.randomUUID();
        Counterparty cp = new Counterparty();
        cp.setId(cpId);
        cp.setName("John Doe");

        UUID l1Id = UUID.randomUUID();
        Lending l1 = new Lending();
        l1.setId(l1Id);
        l1.setCounterparty(cp);
        l1.setDirection(LendingDirection.lent);
        l1.setAmount(new BigDecimal("5000.00"));
        l1.setEntryDate(LocalDate.of(2025, 3, 1));
        l1.setExpectedReturnDate(LocalDate.of(2025, 6, 1));
        l1.setNotes("Lent for project");

        UUID l2Id = UUID.randomUUID();
        Lending l2 = new Lending();
        l2.setId(l2Id);
        l2.setCounterparty(cp);
        l2.setDirection(LendingDirection.borrowed);
        l2.setAmount(new BigDecimal("2000.00"));
        l2.setEntryDate(LocalDate.of(2025, 4, 1));
        l2.setExpectedReturnDate(LocalDate.of(2025, 5, 1));
        l2.setNotes("Borrowed for travel");

        when(lendingService.getAllLendings()).thenReturn(List.of(l1, l2));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(2, rows.size());

        Map<String, Object> r1 = rows.get(0);
        assertEquals(l1Id.toString(), r1.get("id"));
        assertEquals(cpId.toString(), r1.get("counterpartyId"));
        assertEquals("John Doe", r1.get("counterpartyName"));
        assertEquals("lent", r1.get("direction"));
        assertEquals(new BigDecimal("5000.00"), r1.get("amount"));
        assertEquals(new BigDecimal("5000.00"), r1.get("signedAmount"));
        assertEquals(LocalDate.of(2025, 3, 1), r1.get("entryDate"));
        assertEquals(LocalDate.of(2025, 6, 1), r1.get("expectedReturnDate"));
        assertEquals("Lent for project", r1.get("notes"));

        Map<String, Object> r2 = rows.get(1);
        assertEquals(l2Id.toString(), r2.get("id"));
        assertEquals(cpId.toString(), r2.get("counterpartyId"));
        assertEquals("John Doe", r2.get("counterpartyName"));
        assertEquals("borrowed", r2.get("direction"));
        assertEquals(new BigDecimal("2000.00"), r2.get("amount"));
        assertEquals(new BigDecimal("-2000.00"), r2.get("signedAmount"));
        assertEquals(LocalDate.of(2025, 4, 1), r2.get("entryDate"));
        assertEquals(LocalDate.of(2025, 5, 1), r2.get("expectedReturnDate"));
        assertEquals("Borrowed for travel", r2.get("notes"));
    }
}
