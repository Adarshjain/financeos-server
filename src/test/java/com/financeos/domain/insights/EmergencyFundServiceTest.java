package com.financeos.domain.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.api.insights.EmergencyFundResponse.MonthOutflow;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which accounts are liquid, the median of the six months, months covered and the band. */
class EmergencyFundServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Account account(AccountType type, boolean excluded, LocalDate closedOn) {
        Account a = new Account();
        a.setType(type);
        a.setExcludeFromNetAsset(excluded);
        a.setClosedOn(closedOn);
        return a;
    }

    @Test
    void liquidIsOpenIncludedBankOrWalletCash() {
        assertTrue(EmergencyFundService.isLiquid(account(AccountType.bank_account, false, null), TODAY));
        assertTrue(EmergencyFundService.isLiquid(account(AccountType.generic, false, null), TODAY));
        assertTrue(EmergencyFundService.isLiquid(account(null, false, null), TODAY), "untyped reads as generic");
        assertTrue(EmergencyFundService.isLiquid(account(AccountType.bank_account, false, TODAY.plusDays(1)), TODAY),
                "closing tomorrow is still open");
        assertFalse(EmergencyFundService.isLiquid(account(AccountType.bank_account, true, null), TODAY));
        assertFalse(EmergencyFundService.isLiquid(account(AccountType.bank_account, false, TODAY), TODAY));
        assertFalse(EmergencyFundService.isLiquid(account(AccountType.credit_card, false, null), TODAY));
        assertFalse(EmergencyFundService.isLiquid(account(AccountType.broker, false, null), TODAY));
    }

    @Test
    void theMedianOfSixMonthsIsTheMeanOfTheMiddleTwo() {
        assertEquals(0, d("25000").compareTo(EmergencyFundService.median(List.of(
                d("40000"), d("20000"), d("0"), d("30000"), d("10000"), d("90000")))));
        assertEquals(0, d("0").compareTo(EmergencyFundService.median(List.of(
                d("0"), d("0"), d("0"), d("0"), d("100"), d("200")))), "mostly quiet months give zero");
        assertEquals(0, d("5").compareTo(EmergencyFundService.median(List.of(d("1"), d("5"), d("9")))));
        assertEquals(0, BigDecimal.ZERO.compareTo(EmergencyFundService.median(List.of())));
    }

    @Test
    void monthsCoveredIsLiquidOverMedianToOneDecimal() {
        assertEquals(d("4.2"), EmergencyFundService.monthsCovered(d("105000"), d("25000")));
        assertEquals(d("-0.4"), EmergencyFundService.monthsCovered(d("-10000"), d("25000")));
        assertNull(EmergencyFundService.monthsCovered(d("105000"), BigDecimal.ZERO));
        assertNull(EmergencyFundService.monthsCovered(d("105000"), null));
    }

    @Test
    void bandsAreBelowThreeThreeToSixAndSixUp() {
        assertEquals("low", EmergencyFundService.band(d("2.9")));
        assertEquals("low", EmergencyFundService.band(d("-1")));
        assertEquals("medium", EmergencyFundService.band(d("3.0")));
        assertEquals("medium", EmergencyFundService.band(d("5.9")));
        assertEquals("high", EmergencyFundService.band(d("6.0")));
        assertNull(EmergencyFundService.band(null));
    }

    private static Map<YearMonth, BigDecimal> sixMonths() {
        Map<YearMonth, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 6; i >= 1; i--) {
            out.put(YearMonth.of(2026, 10).minusMonths(i), BigDecimal.valueOf(i * 100L));
        }
        return out;
    }

    private static List<Boolean> flags(List<MonthOutflow> months) {
        return months.stream().map(MonthOutflow::beforeHistory).toList();
    }

    @Test
    void monthsBeforeTheFirstTransactionsMonthAreFlaggedAndKeepTheirOrderAndOutflow() {
        List<MonthOutflow> months = EmergencyFundService.months(sixMonths(), YearMonth.of(2026, 8));
        assertEquals(List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09"),
                months.stream().map(MonthOutflow::month).toList());
        assertEquals(List.of(true, true, true, true, false, false), flags(months), "the first month itself is in history");
        assertEquals(0, d("600").compareTo(months.get(0).outflow()));
    }

    @Test
    void historyStartingBeforeTheWindowFlagsNothing() {
        assertEquals(List.of(false, false, false, false, false, false),
                flags(EmergencyFundService.months(sixMonths(), YearMonth.of(2020, 1))));
        assertEquals(List.of(false, false, false, false, false, false),
                flags(EmergencyFundService.months(sixMonths(), YearMonth.of(2026, 4))));
    }

    @Test
    void noTransactionsOrAFirstTransactionThisMonthFlagsEveryMonth() {
        assertEquals(List.of(true, true, true, true, true, true), flags(EmergencyFundService.months(sixMonths(), null)));
        assertEquals(List.of(true, true, true, true, true, true),
                flags(EmergencyFundService.months(sixMonths(), YearMonth.of(2026, 10))));
    }
}
