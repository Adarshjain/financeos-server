package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.report.datasource.impl.NetWorthPlacement;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Row id resolution of {@link NetWorthBreakdownProvider}: account, then loan, then counterparty. */
class NetWorthBreakdownProviderTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private NetWorthAccountBreakdown accounts;
    private NetWorthLoanBreakdown loans;
    private NetWorthLendingBreakdown lendings;
    private NetWorthBreakdownProvider provider;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accounts = mock(NetWorthAccountBreakdown.class);
        loans = mock(NetWorthLoanBreakdown.class);
        lendings = mock(NetWorthLendingBreakdown.class);
        when(accounts.breakdown(any(), anyInt())).thenReturn(Optional.empty());
        when(loans.breakdown(any(), anyInt())).thenReturn(Optional.empty());
        when(lendings.breakdown(any(), anyInt())).thenReturn(Optional.empty());
        when(accounts.section(any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(loans.section(any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(lendings.section(any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
        provider = new NetWorthBreakdownProvider(accounts, loans, lendings);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void explainsTheNetWorthDatasource() {
        assertEquals("net_worth", provider.datasource());
    }

    @Test
    void anAccountIdIsAnsweredByTheAccountsWithoutAskingTheOthers() {
        UUID id = UUID.randomUUID();
        RowBreakdownResponse response = response(id);
        when(accounts.breakdown(id, 25)).thenReturn(Optional.of(response));

        assertSame(response, provider.breakdown(id.toString(), 25));
        verifyNoInteractions(loans, lendings);
    }

    @Test
    void aLoanIdFallsThroughTheAccounts() {
        UUID id = UUID.randomUUID();
        RowBreakdownResponse response = response(id);
        when(loans.breakdown(id, 10)).thenReturn(Optional.of(response));

        assertSame(response, provider.breakdown(id.toString(), 10));
        verifyNoInteractions(lendings);
    }

    @Test
    void aCounterpartyIdFallsThroughAccountsAndLoans() {
        UUID id = UUID.randomUUID();
        RowBreakdownResponse response = response(id);
        when(lendings.breakdown(id, 25)).thenReturn(Optional.of(response));

        assertSame(response, provider.breakdown(id.toString(), 25));
    }

    @Test
    void anIdNoKindAnswersIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> provider.breakdown(UUID.randomUUID().toString(), 25));
        assertThrows(ResourceNotFoundException.class,
                () -> provider.section(UUID.randomUUID().toString(), "transactions", 0, 25));
    }

    @Test
    void aRowIdThatIsNoUuidIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> provider.breakdown("not-a-uuid", 25));
        assertThrows(ResourceNotFoundException.class, () -> provider.section("", "transactions", 0, 25));
        verifyNoInteractions(accounts, loans, lendings);
    }

    @Test
    void sectionsResolveTheSameWay() {
        UUID id = UUID.randomUUID();
        ReportData table = new TableData("TABLE", "raw", List.of(), List.of(), new TableData.Page(1, 5, 0, 1));
        when(loans.section(id, "installments", 1, 5)).thenReturn(Optional.of(table));

        assertSame(table, provider.section(id.toString(), "installments", 1, 5));
        verifyNoInteractions(lendings);
    }

    @Test
    void responseIsTitledByNameSubtitledBySideAndTotalsTheRowValue() {
        UUID id = UUID.randomUUID();
        List<BreakdownStep> steps = List.of(new BreakdownStep("equals", "Balance", null, BigDecimal.TEN, "currency"));

        RowBreakdownResponse asset = NetWorthBreakdownProvider.response(id, "Savings", "Bank account",
                new NetWorthPlacement(FinancialPosition.asset, BigDecimal.TEN), "Balance", steps, List.of(), List.of("n"));
        RowBreakdownResponse liability = NetWorthBreakdownProvider.response(id, "Card", "Credit card",
                new NetWorthPlacement(FinancialPosition.liability, BigDecimal.TEN), "Outstanding", steps, List.of(), List.of());

        assertEquals(new RowBreakdownResponse("net_worth", id.toString(), "Savings", "Asset", "Bank account",
                BigDecimal.TEN, "Balance", "currency", TODAY, steps, List.of(), List.of("n")), asset);
        assertEquals("Liability", liability.subtitle());
        assertEquals("Outstanding", liability.totalLabel());
    }

    private static RowBreakdownResponse response(UUID id) {
        return new RowBreakdownResponse("net_worth", id.toString(), "x", null, null, BigDecimal.ONE, "x", "currency",
                TODAY, List.of(), List.of(), List.of());
    }
}
