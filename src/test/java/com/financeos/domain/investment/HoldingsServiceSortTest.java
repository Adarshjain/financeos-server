package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.HoldingsResponse;
import com.financeos.api.investment.dto.PositionDto;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentService.HoldingDetail;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Holdings list ordering: asset-type group first, then sort key and dir (nulls last), then name and id, then the page. */
class HoldingsServiceSortTest {

    private final InvestmentService investmentService = mock(InvestmentService.class);
    private final HoldingsService service = new HoldingsService(investmentService, mock(InvestmentTransactionRepository.class));

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    /** invested 100; returnAbs = value - 100, so returnPct = returnAbs; invested 0 gives a null return_pct. */
    private static HoldingDetail holding(int n, InstrumentType type, String name, String invested, String value) {
        PositionDto.InstrumentInfoDto inst = new PositionDto.InstrumentInfoDto(id(n), type, name, null, null, null, null, null);
        BigDecimal inv = new BigDecimal(invested);
        BigDecimal val = new BigDecimal(value);
        PositionDto dto = new PositionDto(UUID.randomUUID(), UUID.randomUUID(), "Zerodha", "KITE", inst,
                BigDecimal.ONE, inv, inv, val, null, null, val, val.subtract(inv), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new HoldingDetail(null, dto, List.of(), List.of(), null, List.of());
    }

    private List<String> names(String sort, String dir, Integer page, Integer size, HoldingDetail... rows) {
        when(investmentService.getHoldingDetails(any())).thenReturn(List.of(rows));
        return service.list(null, null, null, sort, dir, page, size).holdings().stream()
                .map(HoldingsResponse.Row::name).toList();
    }

    private HoldingDetail[] sample() {
        return new HoldingDetail[] {
                holding(1, InstrumentType.stock, "Bravo", "100", "110"),   // +10
                holding(2, InstrumentType.stock, "Alpha", "100", "150"),   // +50
                holding(3, InstrumentType.stock, "Charlie", "100", "80"),  // -20
                holding(4, InstrumentType.stock, "Nopct", "0", "5"),       // null pct, abs +5
        };
    }

    @Test
    void defaultsWhenDirAbsent() {
        assertEquals(List.of("Alpha", "Bravo", "Charlie", "Nopct"), names("name", null, null, null, sample()));
        assertEquals(List.of("Alpha", "Bravo", "Charlie", "Nopct"), names(null, null, null, null, sample()));
        assertEquals(List.of("Alpha", "Bravo", "Charlie", "Nopct"), names("return_pct", null, null, null, sample()));
        assertEquals(List.of("Alpha", "Bravo", "Nopct", "Charlie"), names("return_abs", null, null, null, sample()));
    }

    @Test
    void nameBothDirections() {
        assertEquals(List.of("Alpha", "Bravo", "Charlie", "Nopct"), names("name", "asc", null, null, sample()));
        assertEquals(List.of("Nopct", "Charlie", "Bravo", "Alpha"), names("name", "desc", null, null, sample()));
    }

    @Test
    void returnPctBothDirectionsNullsLast() {
        assertEquals(List.of("Alpha", "Bravo", "Charlie", "Nopct"), names("return_pct", "desc", null, null, sample()));
        assertEquals(List.of("Charlie", "Bravo", "Alpha", "Nopct"), names("return_pct", "asc", null, null, sample()));
    }

    @Test
    void returnAbsBothDirections() {
        assertEquals(List.of("Alpha", "Bravo", "Nopct", "Charlie"), names("return_abs", "desc", null, null, sample()));
        assertEquals(List.of("Charlie", "Nopct", "Bravo", "Alpha"), names("return_abs", "asc", null, null, sample()));
    }

    @Test
    void dirIsCaseInsensitive() {
        assertEquals(List.of("Nopct", "Charlie", "Bravo", "Alpha"), names("name", "DeSc", null, null, sample()));
    }

    @Test
    void invalidDirIsRejected() {
        when(investmentService.getHoldingDetails(any())).thenReturn(List.of(sample()));
        assertThrows(ValidationException.class, () -> service.list(null, null, null, "name", "up", null, null));
    }

    @Test
    void invalidSortIsStillRejected() {
        assertThrows(ValidationException.class, () -> service.list(null, null, null, "bogus", "asc", null, null));
    }

    @Test
    void groupsComeFirstAcrossPageBoundaries() {
        HoldingDetail[] rows = {
                holding(1, InstrumentType.etf, "Aetf", "100", "300"),
                holding(2, InstrumentType.mutual_fund, "Zfund", "100", "200"),
                holding(3, InstrumentType.stock, "Zstock", "100", "100"),
                holding(4, InstrumentType.stock, "Astock", "100", "120"),
                holding(5, InstrumentType.mutual_fund, "Afund", "100", "90"),
        };
        // stock, mutual_fund, etf; within a group the key and direction apply
        assertEquals(List.of("Astock", "Zstock"), names("name", "asc", 0, 2, rows));
        assertEquals(List.of("Afund", "Zfund"), names("name", "asc", 1, 2, rows));
        assertEquals(List.of("Aetf"), names("name", "asc", 2, 2, rows));
        // best return overall is the etf, yet it still comes last
        assertEquals(List.of("Astock", "Zstock", "Zfund", "Afund", "Aetf"), names("return_pct", "desc", null, null, rows));
        assertEquals(List.of("Zstock", "Astock", "Afund", "Zfund", "Aetf"), names("return_pct", "asc", null, null, rows));
    }

    @Test
    void tiesBreakByNameThenIdSoPagingIsStable() {
        HoldingDetail[] rows = {
                holding(9, InstrumentType.stock, "Same", "100", "110"),
                holding(3, InstrumentType.stock, "Same", "100", "110"),
                holding(5, InstrumentType.stock, "Other", "100", "110"),
        };
        for (String dir : new String[] {null, "asc", "desc"}) {
            when(investmentService.getHoldingDetails(any())).thenReturn(List.of(rows));
            List<UUID> ids = service.list(null, null, null, "return_pct", dir, null, null).holdings().stream()
                    .map(HoldingsResponse.Row::instrumentId).toList();
            assertEquals(List.of(id(5), id(3), id(9)), ids);
            List<UUID> page0 = service.list(null, null, null, "return_pct", dir, 0, 1).holdings().stream()
                    .map(HoldingsResponse.Row::instrumentId).toList();
            List<UUID> page1 = service.list(null, null, null, "return_pct", dir, 1, 1).holdings().stream()
                    .map(HoldingsResponse.Row::instrumentId).toList();
            assertEquals(List.of(id(5)), page0);
            assertEquals(List.of(id(3)), page1);
        }
    }

    @Test
    void groupTotalsCoverTheWholeSetNotThePage() {
        when(investmentService.getHoldingDetails(any())).thenReturn(List.of(sample()));
        HoldingsResponse r = service.list(null, null, null, "name", "asc", 0, 1);
        assertEquals(1, r.holdings().size());
        assertEquals(4, r.groups().get(0).count());
    }
}
