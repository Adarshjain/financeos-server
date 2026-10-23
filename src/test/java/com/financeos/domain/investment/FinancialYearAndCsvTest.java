package com.financeos.domain.investment;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.util.CsvText;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinancialYearAndCsvTest {

    @Test
    void financialYearBoundariesAreThirtyFirstMarchAndFirstApril() {
        assertEquals(2025, FinancialYear.startYear(LocalDate.of(2026, 3, 31)));
        assertEquals(2026, FinancialYear.startYear(LocalDate.of(2026, 4, 1)));
        assertEquals(2025, FinancialYear.startYear(LocalDate.of(2026, 1, 1)));
        assertEquals(2026, FinancialYear.startYear(LocalDate.of(2026, 12, 31)));
        assertEquals(LocalDate.of(2026, 4, 1), FinancialYear.start(2026));
        assertEquals(LocalDate.of(2027, 3, 31), FinancialYear.end(2026));
        assertEquals("2026-27", FinancialYear.label(2026));
        assertEquals("1999-00", FinancialYear.label(1999));
    }

    @Test
    void taxHarvestCurrentFyUsesTheSharedHelper() {
        assertEquals(2025, TaxHarvestService.currentFy(LocalDate.of(2026, 3, 31)));
        assertEquals(2026, TaxHarvestService.currentFy(LocalDate.of(2026, 4, 1)));
    }

    @Test
    void resolveTurnsFyIntoItsWindowOrPassesFromToThrough() {
        FinancialYear.Range fy = FinancialYear.resolve(2026, null, null);
        assertEquals(LocalDate.of(2026, 4, 1), fy.from());
        assertEquals(LocalDate.of(2027, 3, 31), fy.to());

        LocalDate a = LocalDate.of(2026, 5, 1);
        FinancialYear.Range plain = FinancialYear.resolve(null, a, null);
        assertEquals(a, plain.from());
        assertNull(plain.to());

        FinancialYear.Range none = FinancialYear.resolve(null, null, null);
        assertNull(none.from());
        assertNull(none.to());
    }

    @Test
    void resolveRejectsFyWithFromOrToAndImplausibleYears() {
        assertThrows(ValidationException.class, () -> FinancialYear.resolve(2026, LocalDate.of(2026, 5, 1), null));
        assertThrows(ValidationException.class, () -> FinancialYear.resolve(2026, null, LocalDate.of(2026, 5, 1)));
        assertThrows(ValidationException.class, () -> FinancialYear.resolve(26, null, null));
    }

    @Test
    void csvQuotesCommasQuotesAndLineBreaksAndDoublesQuotes() {
        assertEquals("plain", CsvText.cell("plain"));
        assertEquals("\"a,b\"", CsvText.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvText.cell("say \"hi\""));
        assertEquals("\"two\nlines\"", CsvText.cell("two\nlines"));
        assertEquals("", CsvText.cell(null));
        assertEquals("", CsvText.cell(""));
    }

    @Test
    void csvDefusesSpreadsheetFormulasButLeavesNegativeNumbersAlone() {
        assertEquals("'=SUM(A1)", CsvText.cell("=SUM(A1)"));
        assertEquals("'@cmd", CsvText.cell("@cmd"));
        assertEquals("'+1", CsvText.cell("+1"));
        assertEquals("-550", CsvText.cell("-550"));
    }

    @Test
    void csvTextCellsAlsoDefuseALeadingMinusWhileNumbersStayBare() {
        assertEquals("'-cmd", CsvText.text("-cmd"));
        assertEquals("'-1+1", CsvText.text("-1+1"));
        assertEquals("plain-text", CsvText.text("plain-text"));
        assertEquals("=x", CsvText.text("=x"), "other formula starters are left to cell()");
        assertEquals("", CsvText.text(""));
        assertEquals(null, CsvText.text(null));
        assertEquals("'=x", CsvText.cell(CsvText.text("=x")));
        assertEquals("'-cmd", CsvText.cell(CsvText.text("-cmd")));
        assertEquals("a,'-b\r\n", CsvText.row(Arrays.asList("a", CsvText.text("-b"))));
        assertEquals("-123.45", CsvText.cell("-123.45"));
    }

    @Test
    void csvRowJoinsWithCommasAndEndsWithCrlf() {
        assertEquals("a,\"b,c\",,d\r\n", CsvText.row(Arrays.asList("a", "b,c", null, "d")));
    }
}
