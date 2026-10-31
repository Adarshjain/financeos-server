package com.financeos.gmail.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractParsingTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1,23,456.78|123456.78",
            "Rs.1234|1234",
            "Rs. 1,234.5|1234.5",
            "₹ 1,234|1234",
            "INR 1 234.00|1234.00",
            "USD 40|40",
            "1,234,567.89|1234567.89"})
    void parsesAmountFormats(String raw, String expected) {
        assertThat(ExtractParsing.parseAmount(raw)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void rejectsMissingOrZeroAmounts() {
        assertThat(ExtractParsing.parseAmount(null)).isNull();
        assertThat(ExtractParsing.parseAmount("INR")).isNull();
        assertThat(ExtractParsing.parseAmount("0.00")).isNull();
    }

    @Test
    void normalisesCurrency() {
        assertThat(ExtractParsing.parseCurrency("₹", "XXX")).isEqualTo("INR");
        assertThat(ExtractParsing.parseCurrency("Rs.", "XXX")).isEqualTo("INR");
        assertThat(ExtractParsing.parseCurrency("rs", "XXX")).isEqualTo("INR");
        assertThat(ExtractParsing.parseCurrency("inr", "XXX")).isEqualTo("INR");
        assertThat(ExtractParsing.parseCurrency("usd", "INR")).isEqualTo("USD");
        assertThat(ExtractParsing.parseCurrency("IDR", "INR")).isEqualTo("IDR");
        assertThat(ExtractParsing.parseCurrency(null, "INR")).isEqualTo("INR");
        assertThat(ExtractParsing.parseCurrency(" ", "INR")).isEqualTo("INR");
    }

    @Test
    void last4TakesTheTail() {
        assertThat(ExtractParsing.parseLast4("XXXXXXXX1234")).isEqualTo("1234");
        assertThat(ExtractParsing.parseLast4("XX98")).isEqualTo("98");
        assertThat(ExtractParsing.parseLast4("4000 12** **** 5678")).isEqualTo("5678");
        assertThat(ExtractParsing.parseLast4("XXXX")).isNull();
        assertThat(ExtractParsing.parseLast4(null)).isNull();
    }

    @Test
    void cleansCounterparty() {
        List<Pattern> strip = List.of(Pattern.compile("^(?:Mr|Mrs)\\.?\\s+", Pattern.CASE_INSENSITIVE));
        assertThat(ExtractParsing.cleanCounterparty("  SAMPLE   STORE  . ", null)).isEqualTo("SAMPLE STORE");
        assertThat(ExtractParsing.cleanCounterparty("Mr Sample Person -", strip)).isEqualTo("Sample Person");
        assertThat(ExtractParsing.cleanCounterparty(" . ", null)).isNull();
        assertThat(ExtractParsing.cleanCounterparty(null, null)).isNull();
    }

    @Test
    void parsesFourAndTwoDigitYears() {
        LocalDate sent = LocalDate.of(2026, 3, 10);
        assertThat(ExtractParsing.parseDate("09-03-2026", List.of("dd-MM-yyyy", "dd-MM-yy"), sent)).isEqualTo("2026-03-09");
        assertThat(ExtractParsing.parseDate("09-03-26", List.of("dd-MM-yyyy", "dd-MM-yy"), sent)).isEqualTo("2026-03-09");
        assertThat(ExtractParsing.parseDate("05-jun-2025", List.of("dd-MMM-yyyy"), sent)).isEqualTo("2025-06-05");
        assertThat(ExtractParsing.parseDate("Jun 05, 2025", List.of("MMM dd, yyyy"), sent)).isEqualTo("2025-06-05");
    }

    @Test
    void missingYearTakesSentYearOrTheOneBefore() {
        List<String> f = List.of("dd MMM");
        assertThat(ExtractParsing.parseDate("05 Mar", f, LocalDate.of(2026, 3, 10))).isEqualTo("2026-03-05");
        // sent just after New Year, the alert is about late December
        assertThat(ExtractParsing.parseDate("30 Dec", f, LocalDate.of(2026, 1, 2))).isEqualTo("2025-12-30");
        // one day ahead of the sent date is allowed (time zones)
        assertThat(ExtractParsing.parseDate("11 Mar", f, LocalDate.of(2026, 3, 10))).isEqualTo("2026-03-11");
        // no sent date: a missing year cannot be resolved
        assertThat(ExtractParsing.parseDate("05 Mar", f, null)).isNull();
    }

    @Test
    void neverReturnsADateAfterSentPlusOneDay() {
        assertThat(ExtractParsing.parseDate("20-03-2026", List.of("dd-MM-yyyy"), LocalDate.of(2026, 3, 10))).isNull();
        assertThat(ExtractParsing.parseDate("20-03-27", List.of("dd-MM-yy"), LocalDate.of(2026, 3, 10))).isNull();
    }

    @Test
    void unparseableDateIsNull() {
        assertThat(ExtractParsing.parseDate("not a date", List.of("dd-MM-yyyy"), LocalDate.of(2026, 3, 10))).isNull();
        assertThat(ExtractParsing.parseDate(null, List.of("dd-MM-yyyy"), LocalDate.of(2026, 3, 10))).isNull();
        assertThat(ExtractParsing.parseDate("31-02-2026", List.of("dd-MM-yyyy"), LocalDate.of(2026, 3, 10))).isNull();
    }

    @Test
    void maskedAccountNumbersAreNoCounterparty() {
        assertThat(ExtractParsing.cleanCounterparty("**2358", null)).isNull();
        assertThat(ExtractParsing.cleanCounterparty("account **5678", null)).isNull();
        assertThat(ExtractParsing.cleanCounterparty("A/c XX3006", null)).isNull();
        assertThat(ExtractParsing.cleanCounterparty("XXXXXXXX1234", null)).isNull();
    }

    @Test
    void maskedNamesAndRealNamesStayAsWritten() {
        assertThat(ExtractParsing.cleanCounterparty("XXXXXXXXXXXXSA M", null)).isEqualTo("XXXXXXXXXXXXSA M");
        assertThat(ExtractParsing.cleanCounterparty("Xerox Centre", null)).isEqualTo("Xerox Centre");
        assertThat(ExtractParsing.cleanCounterparty("***1234@okbank", null)).isEqualTo("***1234@okbank");
        assertThat(ExtractParsing.cleanCounterparty("SAMPLE STORE", null)).isEqualTo("SAMPLE STORE");
    }
}
