package com.financeos.domain.epf.passbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeos.domain.epf.passbook.EpfPassbookFormatException.Reason;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class EpfPassbookParserTest {

    private final EpfPassbookParser parser = new EpfPassbookParser();

    private static final List<String> MODERN_HEADER = List.of(
            "Employees Provident Fund Organisation",
            "Establishment ID/Name  MHBAN0012345000 / NORTHWIND LABS PRIVATE LIMITED",
            "Member ID/Name  MHBAN00123450000067890 / ZZQUILL TESTPERSON",
            "UAN  100123454321",
            "Wage Month Transaction Date Transaction Type Particulars EPF Wages EPS Wages Employee Share Employer Share Pension");

    private static byte[] pdf(List<String> lines, String userPassword) throws Exception {
        return PassbookTestPdf.pdf(lines, userPassword);
    }

    private static byte[] pdf(List<String> lines) throws Exception {
        return pdf(lines, null);
    }

    private static List<String> withHeader(String... body) {
        List<String> l = new java.util.ArrayList<>(MODERN_HEADER);
        l.addAll(List.of(body));
        return l;
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void modernPassbookWithContributionsOpeningInterestTotalsAndClosing() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "OB Int. Updated upto 31/03/2025 4,10,920 2,97,210 0",
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250",
                "May-2026 15-06-2026 CR Cont. For Due-Month 062026 60,000 15,000 7,200 5,950 1,250",
                "Int. Updated upto 31/03/2026 32,410 23,770 0",
                "Total Contributions for the year [2025] 14,400 11,900 2,500",
                "Total Withdrawals for the year [2025] 0 0 0",
                "Closing Balance as on 31/03/2026 8,16,910 5,90,000 0")));
        assertThat(p.uan()).isEqualTo("100123454321");
        assertThat(p.memberId()).isEqualTo("MHBAN00123450000067890");
        assertThat(p.establishmentId()).isEqualTo("MHBAN0012345000");
        assertThat(p.establishmentName()).isEqualTo("NORTHWIND LABS PRIVATE LIMITED");
        assertThat(p.rows()).hasSize(4);
        ParsedPassbookRow ob = p.rows().get(0);
        assertThat(ob.type()).isEqualTo(PassbookRowType.OPENING_BALANCE);
        assertThat(ob.creditedOn()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(ob.employee()).isEqualByComparingTo("410920");
        assertThat(ob.employer()).isEqualByComparingTo("297210");
        ParsedPassbookRow c = p.rows().get(1);
        assertThat(c.type()).isEqualTo(PassbookRowType.CONTRIBUTION);
        assertThat(c.wageMonth()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(c.creditedOn()).isEqualTo(LocalDate.of(2026, 5, 15));
        assertThat(c.employee()).isEqualByComparingTo("7200");
        assertThat(c.employer()).isEqualByComparingTo("5950");
        assertThat(c.pension()).isEqualByComparingTo("1250");
        assertThat(c.index()).isEqualTo(1);
        ParsedPassbookRow i = p.rows().get(3);
        assertThat(i.type()).isEqualTo(PassbookRowType.INTEREST);
        assertThat(i.interestFy()).isEqualTo(2025);
        assertThat(i.creditedOn()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(p.closing().employee()).isEqualByComparingTo("816910");
        assertThat(p.closing().employer()).isEqualByComparingTo("590000");
        assertThat(p.closing().asOf()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(p.unrecognisedLines()).isZero();
    }

    @Test
    void yearTotalsWithADateWageMonthOrTypeInFrontAreNotEntries() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250",
                "31-03-2027 Total Contributions for the year [2026] 7,200 5,950 1,250",
                "Mar-2027 CR Total Transfer-Ins for the year [2026] 0 0 0",
                "DR Total Withdrawals for the year [ 2026 ] 0 0 0",
                "31-03-2027 CR Grand Total 7,200 5,950 1,250",
                "15-04-2027 CR [2026] Contributions for the year 7,200 5,950 1,250",
                "Int. Updated upto 31/03/2027 1,410 770 0")));
        assertThat(p.rows()).extracting(ParsedPassbookRow::type)
                .containsExactly(PassbookRowType.CONTRIBUTION, PassbookRowType.INTEREST);
        assertThat(p.unrecognisedLines()).isZero();
    }

    @Test
    void rowsWhoseDueMonthAndAmountsWrapToTheNextLine() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "Sep-2026 15-10-2026 CR Cont. for the Due-Month",
                "102026 60,000 15,000 7,200 5,950 1,250",
                "Oct-2026 14-11-2026 CR Cont. for the Due-Month",
                "112026 60,000 15,000 7,300 6,050 1,250",
                "Int. Updated upto 31/03/2027 [ Provisional Interest 1 ]",
                "0 0 0",
                "31-03-2027 Total Contributions for the year [2026] 14,500 12,000 2,500")));
        assertThat(p.rows()).extracting(ParsedPassbookRow::type)
                .containsExactly(PassbookRowType.CONTRIBUTION, PassbookRowType.CONTRIBUTION);
        assertThat(p.rows().get(0).wageMonth()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(p.rows().get(1).employee()).isEqualByComparingTo("7300");
        assertThat(p.rows().get(1).employer()).isEqualByComparingTo("6050");
        assertThat(p.rows().get(1).pension()).isEqualByComparingTo("1250");
        assertThat(p.unrecognisedLines()).isZero();
    }

    @Test
    void aWrappedRowWithNoAmountsAfterItIsUnread() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "Sep-2026 15-10-2026 CR Cont. for the Due-Month",
                "Oct-2026 14-11-2026 CR Cont. for the Due-Month 112026 60,000 15,000 7,300 6,050 1,250",
                "Nov-2026 15-12-2026 CR Cont. for the Due-Month")));
        assertThat(p.rows()).hasSize(1);
        assertThat(p.unrecognisedLines()).isEqualTo(2);
    }

    @Test
    void olderSlashedHeader() throws Exception {
        ParsedPassbook p = parser.parse(pdf(List.of(
                "PF ACCOUNT NUMBER : KN/BNG/0012345/000/1234567",
                "ESTABLISHMENT NAME : TEST COMPANY PVT LTD",
                "ESTABLISHMENT ID : KN/BNG/0012345",
                "APR-2015 15-05-2015 CR Cont. For Due-Month 052015 12,000 8,000 1,800 1,000 800")));
        assertThat(p.uan()).isNull();
        assertThat(p.memberId()).isEqualTo("KN/BNG/0012345/000/1234567");
        assertThat(p.establishmentId()).isEqualTo("KN/BNG/0012345");
        assertThat(p.establishmentName()).isEqualTo("TEST COMPANY PVT LTD");
        assertThat(p.rows().get(0).wageMonth()).isEqualTo(YearMonth.of(2015, 4));
    }

    @Test
    void withdrawalKinds() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "15-06-2015 DR PF Withdrawal 10,000 8,000 0",
                "20-07-2016 DR Claim: Against PARA 69(1) 5,00,000 3,00,000 0",
                "21-07-2016 DR Claim: Against PARA 10C 0 0 40,000",
                "22-07-2016 DR Advance 2,000 1,000 0")));
        assertThat(p.rows()).extracting(ParsedPassbookRow::type).containsOnly(PassbookRowType.WITHDRAWAL);
        assertThat(p.rows()).extracting(ParsedPassbookRow::withdrawalKind).containsExactly(
                PassbookWithdrawalKind.PARTIAL, PassbookWithdrawalKind.FINAL_SETTLEMENT,
                PassbookWithdrawalKind.PENSION, PassbookWithdrawalKind.PARTIAL);
        assertThat(p.rows().get(0).wageMonth()).isNull();
        assertThat(p.rows().get(0).employee()).isEqualByComparingTo("10000");
    }

    @Test
    void transfersInAndOut() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "01-08-2020 CR Transfer In Form 13 1,20,000 90,000 0",
                "01-09-2022 DR Transfer Out 2,10,000 1,50,000 0")));
        assertThat(p.rows().get(0).type()).isEqualTo(PassbookRowType.TRANSFER_IN);
        assertThat(p.rows().get(1).type()).isEqualTo(PassbookRowType.TRANSFER_OUT);
        assertThat(p.rows().get(1).employee()).isEqualByComparingTo("210000");
    }

    @Test
    void dueMonthFallbackWhenWageMonthMissing() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "15-01-2026 CR Cont. For Due-Month 012026 7,200 5,950 1,250")));
        ParsedPassbookRow r = p.rows().get(0);
        assertThat(r.wageMonth()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(r.employee()).isEqualByComparingTo("7200");
        assertThat(r.employer()).isEqualByComparingTo("5950");
        assertThat(r.pension()).isEqualByComparingTo("1250");
    }

    @Test
    void indianGroupingAndDecimals() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 1,60,000.50 15,000 12,345.67 5,950.25 1,250")));
        ParsedPassbookRow r = p.rows().get(0);
        assertThat(r.employee()).isEqualByComparingTo(bd("12345.67"));
        assertThat(r.employer()).isEqualByComparingTo(bd("5950.25"));
    }

    @Test
    void wrappedParticularsAreJoined() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "20-07-2016 DR Claim: Against 5,00,000 3,00,000 0",
                "PARA 69(1)",
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250")));
        assertThat(p.rows()).hasSize(2);
        assertThat(p.rows().get(0).particulars()).contains("PARA 69(1)");
        assertThat(p.rows().get(0).withdrawalKind()).isEqualTo(PassbookWithdrawalKind.FINAL_SETTLEMENT);
        assertThat(p.rows().get(1).type()).isEqualTo(PassbookRowType.CONTRIBUTION);
    }

    @Test
    void twoThreeAndFiveTrailingNumbers() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "15-05-2026 DR PF Withdrawal 10,000 8,000",
                "15-06-2026 DR PF Withdrawal 10,000 8,000 500",
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250",
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 15,000 7,200 5,950 1,250")));
        assertThat(p.rows()).hasSize(4);
        assertThat(p.rows().get(0).employee()).isEqualByComparingTo("10000");
        assertThat(p.rows().get(0).employer()).isEqualByComparingTo("8000");
        assertThat(p.rows().get(0).pension()).isEqualByComparingTo("0");
        assertThat(p.rows().get(1).pension()).isEqualByComparingTo("500");
        assertThat(p.rows().get(2).employee()).isEqualByComparingTo("7200");
        assertThat(p.rows().get(3).employee()).isEqualByComparingTo("7200");
        assertThat(p.rows().get(3).pension()).isEqualByComparingTo("1250");
    }

    @Test
    void multiYearFileKeepsDocumentOrder() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "OB Int. Updated upto 31/03/2024 1,00,000 70,000 0",
                "Mar-2025 15-04-2024 CR Cont. For Due-Month 042024 60,000 15,000 7,200 5,950 1,250",
                "Int. Updated upto 31/03/2025 9,000 6,000 0",
                "Closing Balance as on 31/03/2025 1,16,200 81,950 0",
                "OB Int. Updated upto 31/03/2025 1,16,200 81,950 0",
                "Apr-2025 15-05-2025 CR Cont. For Due-Month 052025 60,000 15,000 7,200 5,950 1,250",
                "Int. Updated upto 31/03/2026 10,000 7,000 0",
                "Closing Balance as on 31/03/2026 1,33,400 94,900 0")));
        assertThat(p.rows()).extracting(ParsedPassbookRow::type).containsExactly(
                PassbookRowType.OPENING_BALANCE, PassbookRowType.CONTRIBUTION, PassbookRowType.INTEREST,
                PassbookRowType.OPENING_BALANCE, PassbookRowType.CONTRIBUTION, PassbookRowType.INTEREST);
        assertThat(p.rows()).extracting(ParsedPassbookRow::index).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(p.rows().get(2).interestFy()).isEqualTo(2024);
        assertThat(p.rows().get(5).interestFy()).isEqualTo(2025);
        assertThat(p.rows().get(3).creditedOn()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(p.closing().asOf()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void personalDetailLinesAfterARowAreNotContinuations() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250",
                "Father's/Husband Name : ZZFATHER NAMEPERSON",
                "Employee Name : ZZEMP NAMEPERSON",
                "Remarks : ZZREMARK")));
        assertThat(p.rows()).hasSize(1);
        assertThat(p.toString()).doesNotContain("ZZFATHER").doesNotContain("ZZEMP").doesNotContain("ZZREMARK");
    }

    @Test
    void personalNameNeverAppearsInResult() throws Exception {
        ParsedPassbook p = parser.parse(pdf(withHeader(
                "Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250")));
        assertThat(p.toString()).doesNotContain("ZZQUILL").doesNotContain("TESTPERSON");
    }

    @Test
    void notAPassbook() throws Exception {
        assertThatThrownBy(() -> parser.parse(pdf(List.of("Grocery list", "Milk 2", "Eggs 12"))))
                .isInstanceOfSatisfying(EpfPassbookFormatException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_A_PASSBOOK));
    }

    @Test
    void headerButNoRowsIsNotAPassbook() throws Exception {
        assertThatThrownBy(() -> parser.parse(pdf(MODERN_HEADER)))
                .isInstanceOfSatisfying(EpfPassbookFormatException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_A_PASSBOOK));
    }

    @Test
    void passwordProtected() throws Exception {
        byte[] enc = pdf(withHeader("Apr-2026 15-05-2026 CR Cont. For Due-Month 052026 60,000 15,000 7,200 5,950 1,250"), "secret");
        assertThatThrownBy(() -> parser.parse(enc))
                .isInstanceOfSatisfying(EpfPassbookFormatException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.PASSWORD_PROTECTED));
    }

    @Test
    void garbageBytesAreUnreadable() {
        assertThatThrownBy(() -> parser.parse("not a pdf at all".getBytes()))
                .isInstanceOfSatisfying(EpfPassbookFormatException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNREADABLE));
    }
}
