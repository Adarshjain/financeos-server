package com.financeos.domain.nps.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeos.domain.nps.statement.ParsedNpsStatement.Holding;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Layout;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Txn;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class NpsStatementParserTest {

    private static final List<String> PW = List.of("ravi1406");
    private final NpsStatementParser parser = new NpsStatementParser();

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/nps/nps-protean-" + name + ".pdf"));
    }

    private ParsedNpsStatement parse(String name) throws IOException {
        return parser.parse(fixture(name), PW);
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static void assertDec(BigDecimal actual, String expected) {
        assertThat(actual).isNotNull();
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private static List<Txn> txnsOf(ParsedNpsStatement p, String schemeLetter) {
        return p.txns().stream().filter(t -> t.schemeName().contains("SCHEME " + schemeLetter + " ")).toList();
    }

    private static void assertTxn(Txn t, NpsTxnKind kind, String date, String amount, String nav, String units) {
        assertThat(t.kind()).isEqualTo(kind);
        assertThat(t.date()).isEqualTo(LocalDate.parse(date));
        if (amount == null) {
            assertThat(t.amount()).isNull();
        } else {
            assertDec(t.amount(), amount);
        }
        if (nav == null) {
            assertThat(t.nav()).isNull();
        } else {
            assertDec(t.nav(), nav);
        }
        assertDec(t.units(), units);
    }

    @Test
    void wideContribution() throws IOException {
        ParsedNpsStatement p = parse("wide-contribution");
        assertThat(p.pran()).isEqualTo("110012345678");
        assertThat(p.subscriberName()).isEqualTo("RAVI KUMAR SHARMA");
        assertThat(p.periodStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2025, 1, 31));
        assertThat(p.layout()).isEqualTo(Layout.WIDE);
        assertThat(p.schemeChoice()).isEqualTo("AGGRESSIVE CHOICE");
        assertThat(p.tierStatuses()).containsEntry("Tier I Status", "ACTIVE")
                .containsEntry("Tier II Status", "NOT ACTIVATED")
                .containsEntry("Tier II Tax Saver Status", "NOT ACTIVATED");
        assertThat(p.prefs()).hasSize(3);
        assertThat(p.prefs().get(0).schemeName()).isEqualTo("HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME E - TIER I");
        assertThat(p.prefs().get(1).schemeName()).isEqualTo("HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME C - TIER I");
        assertThat(p.prefs().get(2).schemeName()).isEqualTo("HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME G - TIER I");
        assertDec(p.prefs().get(0).percent(), "50.00");
        assertDec(p.prefs().get(1).percent(), "30.00");
        assertDec(p.prefs().get(2).percent(), "20.00");

        var s = p.summary();
        assertDec(s.value(), "473112.25");
        assertThat(s.contributionCount()).isEqualTo(7);
        assertDec(s.totalContribution(), "312750.00");
        assertDec(s.totalWithdrawal(), "0.00");
        assertDec(s.notionalGain(), "160362.25");
        assertDec(s.chargesInUnits(), "612.40");
        assertDec(s.xirrPercent(), "10.84");

        assertThat(p.holdings()).hasSize(3);
        assertHolding(p.holdings().get(0), "SCHEME E - TIER I", "4508.9644", "63.1086", "284554.43", "2025-01-31");
        assertHolding(p.holdings().get(1), "SCHEME C - TIER I", "2157.5713", "41.9907", "90597.93", "2025-01-31");
        assertHolding(p.holdings().get(2), "SCHEME G - TIER I", "2604.4642", "37.6123", "97959.89", "2025-01-31");
        assertThat(p.holdings().get(0).schemeName())
                .isEqualTo("HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME E - TIER I");

        assertThat(p.contributions()).hasSize(1);
        var c = p.contributions().get(0);
        assertThat(c.date()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(c.particulars()).isEqualTo("By Voluntary Contributions");
        assertThat(c.uploadedBy()).isEqualTo("eNPS - Online");
        assertDec(c.employee(), "37350.00");
        assertDec(c.employer(), "0.00");
        assertDec(c.total(), "37350.00");

        assertThat(p.txns()).hasSize(12);
        List<Txn> e = txnsOf(p, "E");
        assertThat(e).hasSize(4);
        assertTxn(e.get(0), NpsTxnKind.OPENING, "2025-01-01", null, null, "4210.5521");
        assertTxn(e.get(1), NpsTxnKind.CHARGE, "2025-01-07", "-41.27", "61.9032", "-0.6667");
        assertThat(e.get(1).description()).isEqualTo("Billing for Q3, 2024-2025");
        assertDec(e.get(1).chargeTotal(), "-61.90");
        assertTxn(e.get(2), NpsTxnKind.CONTRIBUTION, "2025-01-15", "18675.00", "62.4417", "299.0790");
        assertThat(e.get(2).chargeTotal()).isNull();
        assertTxn(e.get(3), NpsTxnKind.CLOSING, "2025-01-31", null, null, "4508.9644");
        List<Txn> cc = txnsOf(p, "C");
        assertTxn(cc.get(0), NpsTxnKind.OPENING, "2025-01-01", null, null, "1890.3307");
        assertTxn(cc.get(1), NpsTxnKind.CHARGE, "2025-01-07", "-12.38", "41.7105", "-0.2968");
        assertTxn(cc.get(2), NpsTxnKind.CONTRIBUTION, "2025-01-15", "11205.00", "41.8820", "267.5374");
        assertTxn(cc.get(3), NpsTxnKind.CLOSING, "2025-01-31", null, null, "2157.5713");
        List<Txn> g = txnsOf(p, "G");
        assertTxn(g.get(0), NpsTxnKind.OPENING, "2025-01-01", null, null, "2405.7712");
        assertTxn(g.get(1), NpsTxnKind.CHARGE, "2025-01-07", "-8.25", "37.4988", "-0.2200");
        assertTxn(g.get(2), NpsTxnKind.CONTRIBUTION, "2025-01-15", "7470.00", "37.5541", "198.9130");
        assertTxn(g.get(3), NpsTxnKind.CLOSING, "2025-01-31", null, null, "2604.4642");
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    void stackedRebalance() throws IOException {
        ParsedNpsStatement p = parse("stacked-rebalance");
        assertStackedHeader(p, "2026-06-01", "2026-06-30");
        var s = p.summary();
        assertDec(s.value(), "435515.84");
        assertThat(s.contributionCount()).isEqualTo(6);
        assertDec(s.totalContribution(), "275400.00");
        assertDec(s.totalWithdrawal(), "0.00");
        assertDec(s.notionalGain(), "160115.84");
        assertThat(s.chargesInUnits()).isNull();
        assertDec(s.xirrPercent(), "10.84");
        assertThat(p.contributions()).isEmpty();
        assertHolding(p.holdings().get(0), "SCHEME E - TIER I DIRECT", "4080.4243", "63.1086", "257509.86", "2026-06-30");
        assertHolding(p.holdings().get(1), "SCHEME C - TIER I DIRECT", "2006.6134", "41.9907", "84259.10", "2026-06-30");
        assertHolding(p.holdings().get(2), "SCHEME G - TIER I DIRECT", "2492.4528", "37.6123", "93746.88", "2026-06-30");
        assertThat(p.holdings().get(0).schemeName())
                .isEqualTo("NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT");

        assertThat(p.txns()).hasSize(9);
        List<Txn> e = txnsOf(p, "E");
        assertThat(e.get(0).schemeName())
                .isEqualTo("NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT");
        assertTxn(e.get(0), NpsTxnKind.OPENING, "2026-06-01", null, null, "4210.5521");
        assertTxn(e.get(1), NpsTxnKind.REBALANCE, "2026-06-14", "-8125.40", "62.4417", "-130.1278");
        assertThat(e.get(1).description())
                .isEqualTo("On account of Rebalancing of Assets as per Regulatory Requirement");
        assertThat(e.get(1).chargeTotal()).isNull();
        assertTxn(e.get(2), NpsTxnKind.CLOSING, "2026-06-30", null, null, "4080.4243");
        List<Txn> c = txnsOf(p, "C");
        assertTxn(c.get(0), NpsTxnKind.OPENING, "2026-06-01", null, null, "1890.3307");
        assertTxn(c.get(1), NpsTxnKind.REBALANCE, "2026-06-14", "4870.15", "41.8820", "116.2827");
        assertTxn(c.get(2), NpsTxnKind.CLOSING, "2026-06-30", null, null, "2006.6134");
        List<Txn> g = txnsOf(p, "G");
        assertTxn(g.get(0), NpsTxnKind.OPENING, "2026-06-01", null, null, "2405.7712");
        assertTxn(g.get(1), NpsTxnKind.REBALANCE, "2026-06-14", "3255.25", "37.5541", "86.6816");
        assertTxn(g.get(2), NpsTxnKind.CLOSING, "2026-06-30", null, null, "2492.4528");
        assertThat(p.warnings()).isEmpty();
    }

    @Test
    void stackedContribution() throws IOException {
        ParsedNpsStatement p = parse("stacked-contribution");
        assertStackedHeader(p, "2026-06-01", "2026-06-30");
        var s = p.summary();
        assertDec(s.value(), "473249.05");
        assertThat(s.contributionCount()).isEqualTo(7);
        assertDec(s.totalContribution(), "312750.00");
        assertDec(s.totalWithdrawal(), "0.00");
        assertDec(s.notionalGain(), "160499.05");
        assertHolding(p.holdings().get(0), "SCHEME E", "4650.1982", "63.1086", "293467.50", "2026-06-30");
        assertHolding(p.holdings().get(1), "SCHEME C", "2017.4111", "41.9907", "84712.50", "2026-06-30");
        assertHolding(p.holdings().get(2), "SCHEME G", "2527.6053", "37.6123", "95069.05", "2026-06-30");
        assertThat(p.contributions()).hasSize(1);
        var c = p.contributions().get(0);
        assertThat(c.date()).isEqualTo(LocalDate.of(2026, 6, 14));
        assertThat(c.particulars()).isEqualTo("By Voluntary Contributions");
        assertThat(c.uploadedBy()).isEqualTo("eNPS - Online");
        assertDec(c.employee(), "37350.00");
        assertDec(c.employer(), "0.00");
        assertDec(c.total(), "37350.00");
        assertTxn(txnsOf(p, "E").get(1), NpsTxnKind.CONTRIBUTION, "2026-06-14", "27452.25", "62.4417", "439.6461");
        assertTxn(txnsOf(p, "C").get(1), NpsTxnKind.CONTRIBUTION, "2026-06-14", "5322.38", "41.8820", "127.0804");
        assertTxn(txnsOf(p, "G").get(1), NpsTxnKind.CONTRIBUTION, "2026-06-14", "4575.37", "37.5541", "121.8341");
    }

    private static void assertStackedHeader(ParsedNpsStatement p, String start, String end) {
        assertThat(p.pran()).isEqualTo("110012345678");
        assertThat(p.subscriberName()).isEqualTo("RAVI KUMAR SHARMA");
        assertThat(p.periodStart()).isEqualTo(LocalDate.parse(start));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.parse(end));
        assertThat(p.layout()).isEqualTo(Layout.STACKED);
        assertThat(p.schemeChoice()).isEqualTo("Life Cycle 75 - High (15E / 55 Y)");
        assertThat(p.tierStatuses()).containsEntry("Tier I Status", "ACTIVE");
        assertThat(p.prefs()).hasSize(3);
        assertThat(p.prefs().get(0).schemeName())
                .isEqualTo("NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT");
        assertThat(p.prefs().get(1).schemeName())
                .isEqualTo("NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME C - TIER I DIRECT");
        assertThat(p.prefs().get(2).schemeName())
                .isEqualTo("NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME G - TIER I DIRECT");
        assertDec(p.prefs().get(0).percent(), "73.50");
        assertDec(p.prefs().get(1).percent(), "14.25");
        assertDec(p.prefs().get(2).percent(), "12.25");
        assertThat(p.holdings()).hasSize(3);
    }

    private static void assertHolding(Holding h, String nameEnd, String units, String nav, String value, String navDate) {
        assertThat(h.schemeName()).contains(nameEnd);
        assertDec(h.units(), units);
        assertDec(h.nav(), nav);
        assertDec(h.value(), value);
        assertThat(h.navDate()).isEqualTo(LocalDate.parse(navDate));
    }

    @Test
    void wideEmptyMonthHasOnlyOpeningAndClosing() throws IOException {
        // Months without transactions have no charge column, so the header sits further from the data rows.
        ParsedNpsStatement p = parse("wide-empty");
        assertThat(p.layout()).isEqualTo(Layout.WIDE);
        assertThat(p.periodStart()).isEqualTo(LocalDate.of(2025, 2, 1));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(p.warnings()).isEmpty();
        assertThat(p.contributions()).isEmpty();
        assertDec(p.summary().totalContribution(), "312750.00");
        assertThat(p.summary().chargesInUnits()).isNull();
        assertHolding(p.holdings().get(0), "SCHEME E - TIER I", "4508.9644", "63.1086", "284554.43", "2025-02-28");
        assertThat(p.txns()).hasSize(6);
        assertTxn(txnsOf(p, "E").get(0), NpsTxnKind.OPENING, "2025-02-01", null, null, "4508.9644");
        assertTxn(txnsOf(p, "E").get(1), NpsTxnKind.CLOSING, "2025-02-28", null, null, "4508.9644");
        assertTxn(txnsOf(p, "C").get(0), NpsTxnKind.OPENING, "2025-02-01", null, null, "2157.5713");
        assertTxn(txnsOf(p, "G").get(1), NpsTxnKind.CLOSING, "2025-02-28", null, null, "2604.4642");
    }

    @Test
    void unitsReconcileForEveryFixture() throws IOException {
        for (String f : List.of("wide-contribution", "wide-empty", "stacked-rebalance", "stacked-contribution")) {
            ParsedNpsStatement p = parse(f);
            for (Holding h : p.holdings()) {
                List<Txn> own = p.txns().stream().filter(t -> t.schemeName().equals(h.schemeName())).toList();
                assertThat(own).as(f + " " + h.schemeName()).isNotEmpty();
                BigDecimal opening = own.stream().filter(t -> t.kind() == NpsTxnKind.OPENING).findFirst().orElseThrow().units();
                BigDecimal closing = own.stream().filter(t -> t.kind() == NpsTxnKind.CLOSING).findFirst().orElseThrow().units();
                BigDecimal moved = own.stream()
                        .filter(t -> t.kind() != NpsTxnKind.OPENING && t.kind() != NpsTxnKind.CLOSING)
                        .map(Txn::units).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(opening.add(moved).subtract(closing).abs().doubleValue()).as(f).isLessThanOrEqualTo(0.0001);
                assertThat(h.units().subtract(closing).abs().doubleValue()).as(f).isLessThanOrEqualTo(0.0001);
            }
        }
    }

    @Test
    void wrongPasswordIsLocked() throws IOException {
        byte[] pdf = fixture("wide-contribution");
        assertThatThrownBy(() -> parser.parse(pdf, List.of("nope1234", "other999")))
                .isInstanceOf(NpsStatementLockedException.class);
        assertThatThrownBy(() -> parser.parse(pdf, List.of())).isInstanceOf(NpsStatementLockedException.class);
    }

    @Test
    void secondCandidatePasswordWorks() throws IOException {
        assertThat(parser.parse(fixture("wide-contribution"), List.of("wrong", "ravi1406")).pran())
                .isEqualTo("110012345678");
    }

    @Test
    void nonNpsPdfIsFormatError() throws IOException {
        byte[] pdf = plainPdf("Quarterly newsletter. Nothing to see here.");
        assertThatThrownBy(() -> parser.parse(pdf, List.of("ravi1406")))
                .isInstanceOf(NpsStatementFormatException.class);
    }

    @Test
    void notAPdfIsFormatError() {
        assertThatThrownBy(() -> parser.parse("hello".getBytes(), List.of("x")))
                .isInstanceOf(NpsStatementFormatException.class);
    }

    @Test
    void kindClassification() {
        assertThat(NpsStatementParser.kindOf("Opening balance")).isEqualTo(NpsTxnKind.OPENING);
        assertThat(NpsStatementParser.kindOf("Closing Balance")).isEqualTo(NpsTxnKind.CLOSING);
        assertThat(NpsStatementParser.kindOf("Billing for Q3, 2024-2025")).isEqualTo(NpsTxnKind.CHARGE);
        assertThat(NpsStatementParser.kindOf("On account of Rebalancing of Assets")).isEqualTo(NpsTxnKind.REBALANCE);
        assertThat(NpsStatementParser.kindOf("Multiple NAV Framework migration")).isEqualTo(NpsTxnKind.MIGRATION);
        assertThat(NpsStatementParser.kindOf("Credit of units due to implementation of X")).isEqualTo(NpsTxnKind.MIGRATION);
        assertThat(NpsStatementParser.kindOf("Switch out")).isEqualTo(NpsTxnKind.SWITCH);
        assertThat(NpsStatementParser.kindOf("To Unit Redemption")).isEqualTo(NpsTxnKind.WITHDRAWAL);
        assertThat(NpsStatementParser.kindOf("Employer Contribution")).isEqualTo(NpsTxnKind.EMPLOYER_CONTRIBUTION);
        assertThat(NpsStatementParser.kindOf("By Voluntary Contributions")).isEqualTo(NpsTxnKind.CONTRIBUTION);
        assertThat(NpsStatementParser.kindOf("Something new")).isEqualTo(NpsTxnKind.OTHER);
    }

    @Test
    void numberParsing() {
        assertThat(NpsStatementParser.num("(1,234.56)", 2)).isEqualByComparingTo(d("-1234.56"));
        assertThat(NpsStatementParser.num("-1,234.56", 2)).isEqualByComparingTo(d("-1234.56"));
        assertThat(NpsStatementParser.num("4,35,515.84", 2)).isEqualByComparingTo(d("435515.84"));
        assertThat(NpsStatementParser.shortDate("01-Jan-25")).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(NpsStatementParser.shortDate("nonsense")).isNull();
    }

    private static byte[] plainPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
