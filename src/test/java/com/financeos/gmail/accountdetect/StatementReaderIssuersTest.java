package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.StatementPdfReader.Result;
import com.financeos.gmail.accountdetect.StatementPdfReader.Status;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement reader for every issuer family: made-up statements in each issuer's usual layout (title, masked number,
 * date notation, own domain / IFSC), built as real PDFs; plus the documents that must never be read as statements.
 */
class StatementReaderIssuersTest {

    static byte[] pdf(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int i = 0;
            while (i < lines.size() || i == 0) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                    cs.newLineAtOffset(30, 780);
                    for (int n = 0; n < 60 && i < lines.size(); n++, i++) {
                        cs.showText(lines.get(i).replace("₹", "Rs."));
                        cs.newLineAtOffset(0, -12);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static Result read(List<String> lines) throws Exception {
        return StatementPdfReader.read(pdf(lines), "PDF_FOLDER", null, null);
    }

    // ------------------------------------------------------------------ one card statement per issuer

    /** issuer id, the title as printed, the card number line, footer domain, expected last4. */
    record CardCase(String bank, String title, String number, String domain, String last4) {}

    private static final String[] DATE_LINES = {
            "Statement Date: 15/09/2026",
            "Statement Date 15 Sep, 2026",
            "Statement Date: 15-Sep-2026",
            "Statement Period: 16/08/2026 to 15/09/2026",
            "Billing Period 16 Aug, 2026 - 15 Sep, 2026",
            "Statement Date: 15.09.2026",
            "Statement Date: Sep 15, 2026",
            "Statement Date : 15-09-26",
    };

    static final List<CardCase> CARDS = List.of(
            new CardCase("hdfc", "HDFC Bank Regalia Gold Credit Card Statement", "Card No: 5522 12XX XXXX 4381", "www.hdfcbank.com", "4381"),
            new CardCase("icici", "ICICI Bank Credit Card Statement", "Card No: 4457 87XX XXXX 0045", "www.icicibank.com", "0045"),
            new CardCase("sbi", "SBI Card Statement", "Credit Card Number: XXXX XXXX XXXX 6672", "www.sbicard.com", "6672"),
            new CardCase("axis", "Axis Bank ACE Credit Card Statement", "Card No: 408791******9894", "www.axisbank.com", "9894"),
            new CardCase("kotak", "Kotak Mahindra Bank Credit Card Statement", "Card Number: 5241 83XX XXXX 7710", "www.kotak.com", "7710"),
            new CardCase("idfc_first", "IDFC FIRST Bank Credit Card Statement", "Card Number: 5307 XXXX XXXX 3318", "www.idfcfirstbank.com", "3318"),
            new CardCase("indusind", "IndusInd Bank Credit Card Statement", "Card No: 4147 XXXX XXXX 2290", "www.indusind.com", "2290"),
            new CardCase("au", "AU Small Finance Bank Credit Card Statement", "Card Number: XXXX XXXX XXXX 5183", "www.aubank.in", "5183"),
            new CardCase("yes", "YES BANK Credit Card Statement", "Statement for YES BANK Card Number 4592XXXXXXXX2757", "www.yesbank.in", "2757"),
            new CardCase("rbl", "RBL Bank Credit Card Statement", "Card No: 5236 XXXX XXXX 9054", "www.rblbank.com", "9054"),
            new CardCase("federal", "Federal Bank Credit Card Statement", "Card No: 4300 XXXX XXXX 1126", "www.federalbank.co.in", "1126"),
            new CardCase("bank_of_baroda", "BOBCARD Credit Card Statement", "Card No: 5522 XXXX XXXX 2358", "www.bobcard.co.in", "2358"),
            new CardCase("amex", "American Express Platinum Travel Credit Card Statement", "Card Number: XXXX-XXXXXX-X1008", "www.americanexpress.com", "1008"),
            new CardCase("hsbc", "HSBC Credit Card Statement", "Card Number: 4146 XXXX XXXX 4650", "www.hsbc.co.in", "4650"),
            new CardCase("sc", "Standard Chartered Bank Credit Card Statement", "Card Number: 5290 XXXX XXXX 3406", "www.sc.com", "3406"),
            new CardCase("dbs", "DBS Bank Credit Card Statement", "Card Number: 4041 XXXX XXXX 7712", "www.dbs.com", "7712"),
            new CardCase("pnb", "Punjab National Bank Credit Card Statement", "Card No: 5176 XXXX XXXX 8841", "www.pnbindia.in", "8841"),
            new CardCase("canara", "Canara Bank Credit Card Statement", "Card No: 4033 XXXX XXXX 6290", "www.canarabank.com", "6290"),
            new CardCase("union_bank", "Union Bank of India Credit Card Statement", "Card No: 4291 XXXX XXXX 3317", "www.unionbankofindia.co.in", "3317"),
            new CardCase("boi", "Bank of India Credit Card Statement", "Card No: 5123 XXXX XXXX 7745", "www.bankofindia.co.in", "7745"),
            new CardCase("idbi", "IDBI Bank Credit Card Statement", "Card No: 4098 XXXX XXXX 1904", "www.idbibank.in", "1904"),
            new CardCase("bandhan", "Bandhan Bank Credit Card Statement", "Card No: 5287 XXXX XXXX 4417", "www.bandhanbank.com", "4417"),
            new CardCase("sbm", "SBM Bank India Credit Card Statement", "Card No: 5368 XXXX XXXX 8062", "www.sbmbank.co.in", "8062"),
            new CardCase("onecard", "OneCard Statement", "Card Number: 5409 XXXX XXXX 2276", "www.getonecard.app", "2276"),
            new CardCase("jupiter", "Jupiter Money Edge CSB Credit Card Statement", "Card Number: 4568 XXXX XXXX 3315", "jupiter.money", "3315"),
            new CardCase("fi", "Fi Money Federal Credit Card Statement", "Card Number: 5231 XXXX XXXX 9908", "fi.money", "9908"),
            new CardCase("equitas", "Equitas Small Finance Bank Credit Card Statement", "Card No: 4388 XXXX XXXX 5061", "www.equitasbank.com", "5061"),
            new CardCase("ujjivan", "Ujjivan Small Finance Bank Credit Card Statement", "Card No: 4711 XXXX XXXX 6720", "www.ujjivansfb.in", "6720"),
            new CardCase("utkarsh", "Utkarsh Small Finance Bank Credit Card Statement", "Card No: 4944 XXXX XXXX 3188", "www.utkarsh.bank", "3188"),
            new CardCase("suryoday", "Suryoday Small Finance Bank Credit Card Statement", "Card No: 5011 XXXX XXXX 7239", "www.suryodaybank.com", "7239"),
            new CardCase("karnataka", "Karnataka Bank Credit Card Statement", "Card No: 4219 XXXX XXXX 8354", "www.karnatakabank.com", "8354"),
            new CardCase("kvb", "Karur Vysya Bank Credit Card Statement", "Card No: 4560 XXXX XXXX 2073", "www.kvb.co.in", "2073"),
            new CardCase("cub", "City Union Bank Credit Card Statement", "Card No: 4725 XXXX XXXX 6419", "www.cityunionbank.com", "6419"),
            new CardCase("south_indian", "South Indian Bank Credit Card Statement", "Card No: 5102 XXXX XXXX 3987", "www.southindianbank.com", "3987"),
            new CardCase("dcb", "DCB Bank Credit Card Statement", "Card No: 4876 XXXX XXXX 1542", "www.dcbbank.com", "1542"),
            new CardCase("indian_bank", "Indian Bank Credit Card Statement", "Card No: 4902 XXXX XXXX 7183", "www.indianbank.in", "7183"));

    private static List<String> cardLines(CardCase c, String dateLine) {
        List<String> l = new ArrayList<>();
        l.add(c.title);
        l.add("Mr Sample Holder");
        l.add(c.number);
        l.add(dateLine);
        l.add("Payment Due Date: 05/10/2026");
        l.add("Total Amount Due: Rs. 12,345.00");
        l.add("Minimum Amount Due: Rs. 620.00");
        l.add("Credit Limit: Rs. 2,50,000.00");
        l.add("Available Credit Limit: Rs. 1,90,000.00");
        l.add("Reward Points Summary");
        l.add("Date Transaction Details Amount");
        l.add("02/09/2026 SAMPLE STORE 1,200.00 Dr");
        l.add("For queries write to us or visit " + c.domain);
        return l;
    }

    @Test
    void everyIssuerCardStatementGetsBankLast4KindAndPeriod() throws Exception {
        for (int i = 0; i < CARDS.size(); i++) {
            CardCase c = CARDS.get(i);
            Result r = read(cardLines(c, DATE_LINES[i % DATE_LINES.length]));
            assertThat(r.status()).as(c.bank() + " " + r.flags()).isEqualTo(Status.OPENED);
            StatementContent s = r.content();
            assertThat(s.bank()).as(c.bank()).isEqualTo(BankPartners.resolveBank(c.bank(), null));   // a Fi statement reads as its only partner bank
            assertThat(s.last4()).as(c.bank() + " last4").isEqualTo(c.last4());
            assertThat(s.kind()).as(c.bank() + " kind").isEqualTo(Kind.CREDIT_CARD);
            assertThat(s.period()).as(c.bank() + " period").isEqualTo(YearMonth.of(2026, 9));
            assertThat(s.limitBucket()).as(c.bank() + " limit").isEqualTo("1-3L");
        }
    }

    // ------------------------------------------------------------------ account statements

    /** id, name as printed, IFSC prefix. */
    record AcctCase(String bank, String name, String ifsc) {}

    static final List<AcctCase> ACCOUNTS = List.of(
            new AcctCase("hdfc", "HDFC BANK LIMITED", "HDFC0001234"), new AcctCase("icici", "ICICI Bank Limited", "ICIC0000104"),
            new AcctCase("sbi", "State Bank of India", "SBIN0001234"), new AcctCase("axis", "Axis Bank Ltd", "UTIB0000123"),
            new AcctCase("kotak", "Kotak Mahindra Bank", "KKBK0000958"), new AcctCase("idfc_first", "IDFC FIRST Bank", "IDFB0040101"),
            new AcctCase("indusind", "IndusInd Bank", "INDB0000018"), new AcctCase("au", "AU Small Finance Bank", "AUBL0002001"),
            new AcctCase("yes", "YES BANK", "YESB0000001"), new AcctCase("rbl", "RBL Bank", "RATN0000001"),
            new AcctCase("federal", "Federal Bank", "FDRL0001001"), new AcctCase("bank_of_baroda", "Bank of Baroda", "BARB0MAINXX"),
            new AcctCase("hsbc", "HSBC", "HSBC0400002"), new AcctCase("sc", "Standard Chartered Bank", "SCBL0036001"),
            new AcctCase("dbs", "DBS Bank India", "DBSS0IN0811"), new AcctCase("pnb", "Punjab National Bank", "PUNB0012300"),
            new AcctCase("canara", "Canara Bank", "CNRB0000123"), new AcctCase("union_bank", "Union Bank of India", "UBIN0531234"),
            new AcctCase("boi", "Bank of India", "BKID0000123"), new AcctCase("idbi", "IDBI Bank", "IBKL0000123"),
            new AcctCase("bandhan", "Bandhan Bank", "BDBL0001234"), new AcctCase("sbm", "SBM Bank India", "STCB0000001"),
            new AcctCase("equitas", "Equitas Small Finance Bank", "ESFB0001001"), new AcctCase("ujjivan", "Ujjivan Small Finance Bank", "UJVN0001001"),
            new AcctCase("utkarsh", "Utkarsh Small Finance Bank", "UTKS0001001"), new AcctCase("suryoday", "Suryoday Small Finance Bank", "SURY0000001"),
            new AcctCase("karnataka", "Karnataka Bank", "KARB0000123"), new AcctCase("kvb", "Karur Vysya Bank", "KVBL0001234"),
            new AcctCase("cub", "City Union Bank", "CIUB0000123"), new AcctCase("south_indian", "South Indian Bank", "SIBL0000123"),
            new AcctCase("dcb", "DCB Bank", "DCBL0000123"), new AcctCase("airtel_payments", "Airtel Payments Bank", "AIRP0000001"),
            new AcctCase("paytm_payments", "Paytm Payments Bank", "PYTM0123456"), new AcctCase("indian_bank", "Indian Bank", "IDIB000A123"));

    private static List<String> acctLines(AcctCase a, String period) {
        return List.of(a.name, "Account Statement " + period, "Account Number: 50100123456784821", "Branch: Sample Branch  IFSC: " + a.ifsc,
                "Opening Balance: 10,000.00   Closing Balance: 12,500.00", "Date Narration Withdrawals Deposits Balance",
                "02/09/2026 UPI SAMPLE 200.00 10,200.00", "Total Withdrawals 1,200.00 Total Deposits 3,700.00");
    }

    @Test
    void everyBankAccountStatementIsAnAccountWithItsPeriodEnd() throws Exception {
        String[] periods = {"01/09/2026 to 30/09/2026", "01 Sep 2026 to 30 Sep 2026", "From 01-09-2026 To 30-09-2026", "Sep 01, 2026 - Sep 30, 2026"};
        for (int i = 0; i < ACCOUNTS.size(); i++) {
            AcctCase a = ACCOUNTS.get(i);
            Result r = read(acctLines(a, periods[i % periods.length]));
            assertThat(r.status()).as(a.bank() + " " + r.flags()).isEqualTo(Status.OPENED);
            StatementContent s = r.content();
            assertThat(s.bank()).as(a.bank() + " " + r.flags()).isEqualTo(a.bank());
            assertThat(s.kind()).as(a.bank() + " kind " + r.flags()).isEqualTo(Kind.ACCOUNT);
            assertThat(s.period()).as(a.bank() + " period").isEqualTo(YearMonth.of(2026, 9));
            assertThat(s.last4()).as(a.bank() + " last4").isEqualTo("4821");
            assertThat(s.limitBucket()).isNull();
        }
    }

    @Test
    void ifscPrefixAloneNamesTheBankOfAnAccountStatement() throws Exception {
        Result r = read(List.of("Statement of Account", "Period: 01/09/2026 to 30/09/2026", "Account No: 50100123456789", "IFSC: UTIB0000123 MICR: 400211001",
                "Opening Balance 100.00 Closing Balance 200.00", "Date Narration Withdrawals Deposits Balance"));
        assertThat(r.status()).isEqualTo(Status.OPENED);
        assertThat(r.content().bank()).isEqualTo("axis");
        assertThat(r.content().kind()).isEqualTo(Kind.ACCOUNT);
    }

    // ------------------------------------------------------------------ traps

    @Test
    void anotherBanksNetBankingInTheFooterDoesNotWin() throws Exception {
        List<String> l = cardLines(CARDS.get(1), DATE_LINES[0]);
        l.add("Pay via HDFC NetBanking or HDFC Bank bill payment; pay through Axis Bank UPI");
        l.add("Cheque in favour of ICICI Bank Credit Card payable at par; NEFT to any HDFC Bank branch");
        for (int i = 0; i < 10; i++) l.add("Pay using HDFC NetBanking option " + i + " at www.hdfcbank.com");
        Result r = read(l);
        assertThat(r.content().bank()).isEqualTo("icici");
    }

    @Test
    void footerBeneficiaryTableWithOtherBanksIfscIsNotTheIssuer() throws Exception {
        List<String> l = cardLines(CARDS.get(3), DATE_LINES[3]);
        for (int i = 0; i < 20; i++) l.add("filler transaction row " + i);
        l.add("NEFT beneficiary: Airtel Payments Bank IFSC AIRP0000001 account 99900011122");
        l.add("Airtel Payments Bank IFSC AIRP0000001 branch Delhi");
        l.add("HDFC Bank IFSC HDFC0000001 account 99900011133");
        assertThat(read(l).content().bank()).isEqualTo("axis");
    }

    @Test
    void exCitiStatementIsAxis() throws Exception {
        List<String> l = new ArrayList<>(List.of("Axis Bank Credit Card Statement", "(formerly Citibank India Consumer Business)", "Card No: 5522 XXXX XXXX 6612",
                "Statement Date: 15/09/2026", "Total Amount Due: Rs. 5,000.00", "Minimum Amount Due: Rs. 250.00", "Credit Limit: Rs. 3,00,000.00", "www.axisbank.com"));
        Result r = read(l);
        assertThat(r.content().bank()).isEqualTo("axis");
        assertThat(r.content().last4()).isEqualTo("6612");
        assertThat(r.content().limitBucket()).isEqualTo("3-5L");
    }

    @Test
    void reserveBankOfIndiaAndStateBankOfIndiaAreNotBankOfIndia() throws Exception {
        List<String> l = new ArrayList<>(List.of("State Bank of India", "Account Statement 01/09/2026 to 30/09/2026", "IFSC: SBIN0001234", "Account Number: 30000123456781234",
                "Opening Balance 100.00 Closing Balance 200.00", "As per Reserve Bank of India guidelines, report errors within 30 days",
                "Union Bank of India and Bank of India are partner ATM networks"));
        Result r = read(l);
        assertThat(r.content().bank()).isEqualTo("sbi");
    }

    @Test
    void twoBanksNamedEquallyIsUnknown() throws Exception {
        Result r = read(List.of("HDFC Bank and ICICI Bank joint statement", "Statement Date: 15/09/2026", "Total Amount Due: Rs. 5,000.00", "Minimum Amount Due: Rs. 250.00"));
        assertThat(r.status()).isEqualTo(Status.NO_BANK);
        assertThat(r.content()).isNull();
    }

    @Test
    void noIssuerAtAllIsUnknown() throws Exception {
        Result r = read(List.of("Credit Card Statement", "Statement Date: 15/09/2026", "Total Amount Due: Rs. 5,000.00", "Minimum Amount Due: Rs. 250.00"));
        assertThat(r.status()).isEqualTo(Status.NO_BANK);
    }

    @Test
    void termsAndConditionsAreNotAStatement() throws Exception {
        List<String> l = new ArrayList<>(List.of("HDFC Bank Credit Card", "Terms and Conditions", "Your Credit Limit is assigned by the Bank.",
                "The Minimum Amount Due is 5% of the total dues; the Total Amount Due must be paid by the Payment Due Date.",
                "The statement date is the day your bill is generated. Statement Date: the 15th of each month."));
        Result r = read(l);
        assertThat(r.status()).isEqualTo(Status.OPENED);
        assertThat(r.content().kind()).isNull();
        assertThat(r.content().limitBucket()).isNull();
    }

    @Test
    void mitcAndKfsAreNotStatements() throws Exception {
        for (String title : List.of("Most Important Terms and Conditions (MITC)", "Key Fact Statement (KFS)", "Welcome Kit")) {
            List<String> l = new ArrayList<>(List.of("ICICI Bank Credit Card", title, "Date: 15/09/2026", "Annual fee Rs. 500", "Minimum Amount Due: 5% of Total Amount Due",
                    "Credit Limit: as sanctioned", "Payment Due Date: 18 days after statement date", "Reward Points: 2 per Rs. 100", "Statement Date: 15/09/2026"));
            Result r = read(l);
            assertThat(r.content()).as(title).isNotNull();
            assertThat(r.content().kind()).as(title).isNull();
        }
    }

    @Test
    void proseWithStatementWordsAndNoLabelledDateIsNotAStatement() throws Exception {
        List<String> l = new ArrayList<>(List.of("Axis Bank Credit Card", "Your statement will be generated on the 15th of every month and the minimum amount due",
                "must be paid by 05/10/2026 failing which charges apply.", "Total Amount Due", "Credit Limit"));
        assertThat(read(l).content().kind()).isNull();
    }

    @Test
    void loanStatementIsNotACardOrAccountStatement() throws Exception {
        List<String> l = new ArrayList<>(List.of("HDFC Bank Limited", "Loan Account Statement 01/04/2026 to 30/09/2026", "Loan Account Number: 5000123456",
                "Principal Outstanding: 4,50,000.00", "Rate of Interest: 10.5%", "EMI: 12,500.00", "Closing Balance: 4,40,000.00"));
        Result r = read(l);
        assertThat(r.content().kind()).isNull();
    }

    @Test
    void creditLimitInTheFooterIsIgnored() throws Exception {
        List<String> l = new ArrayList<>(cardLines(CARDS.get(0), DATE_LINES[0]));
        l.removeIf(s -> s.contains("Credit Limit"));
        for (int i = 0; i < 45; i++) l.add("Transaction row " + i + " 02/09/2026 STORE " + (i + 10) + ".00");
        l.add("Your Credit Limit: Rs. 9,99,999.00 may change at the bank's discretion");
        assertThat(read(l).content().limitBucket()).isNull();
    }

    // ------------------------------------------------------------------ dates

    @Test
    void everyDateNotationIsRead() throws Exception {
        List<String> lines = List.of("Statement Date: 15/09/2026", "Statement Date: 15-09-26", "Statement Date: 15.09.2026", "Statement Date: 15 Sep 2026",
                "Statement Date: 15-Sep-2026", "Statement Date: 15 Sep, 2026", "Statement Date: Sep 15, 2026", "Statement Date: September 15, 2026",
                "Statement Date: 15 September 2026", "Statement Date: 2026-09-15", "Statement Date: 15Sep2026", "Statement Date: 15 Sep '26",
                "Billing Period: 16/08/2026 - 15/09/2026", "Statement Period: 16 Aug 2026 to 15 Sep 2026", "Statement Period from 16-Aug-2026 to 15-Sep-2026",
                "Statement for the period 16.08.2026 to 15.09.2026", "Billing Period 16 Aug, 2026 - 15 Sep, 2026");
        for (String d : lines) {
            List<String> l = List.of("Kotak Mahindra Bank Credit Card Statement", "Card No: 5241 83XX XXXX 7710", d, "Total Amount Due: Rs. 100.00", "Minimum Amount Due: Rs. 10.00");
            Result r = read(l);
            assertThat(r.content().period()).as(d).isEqualTo(YearMonth.of(2026, 9));
            assertThat(r.content().periodEnd()).as(d).isEqualTo(java.time.LocalDate.of(2026, 9, 15));
            assertThat(r.content().kind()).as(d).isEqualTo(Kind.CREDIT_CARD);
        }
    }

    @Test
    void paymentDueDateIsNeverTheStatementDate() throws Exception {
        Result r = read(List.of("Axis Bank Credit Card Statement", "Card No: 408791******9894", "Payment Due Date: 05/10/2026", "Statement Date: 15/09/2026",
                "Total Amount Due: Rs. 100.00", "Minimum Amount Due: Rs. 10.00"));
        assertThat(r.content().periodEnd()).isEqualTo(java.time.LocalDate.of(2026, 9, 15));
        Result onlyDue = read(List.of("Axis Bank Credit Card Statement", "Card No: 408791******9894", "Payment Due Date: 05/10/2026",
                "Total Amount Due: Rs. 100.00", "Minimum Amount Due: Rs. 10.00"));
        assertThat(onlyDue.content().periodEnd()).isNull();
        assertThat(onlyDue.content().kind()).isNull();
    }

    @Test
    void labelRowWithValuesInTheRowBelow() throws Exception {
        // Axis: one row of labels, the values (amounts and dates) in the next row
        Result r = read(List.of("Axis Bank ACE Credit Card Statement",
                "Total Payment Due Minimum Payment Due Statement Period Payment Due Date Statement Generation Date",
                "Rs. 12,345.00 Rs. 620.00 16/08/2026 - 15/09/2026 05/10/2026 15/09/2026",
                "Credit Card Number Credit Limit Available Credit Limit Available Cash Limit",
                "408791******9894 Rs. 2,50,000.00 Rs. 1,90,000.00 Rs. 50,000.00"));
        assertThat(r.content().period()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(r.content().kind()).isEqualTo(Kind.CREDIT_CARD);
        assertThat(r.content().limitBucket()).isEqualTo("1-3L");
        assertThat(r.content().last4()).isEqualTo("9894");
    }

    @Test
    void periodSpanningMonthEdgeUsesTheEndNotTheGenerationDate() throws Exception {
        Result r = read(List.of("HSBC Credit Card Statement", "Card Number: 4146 XXXX XXXX 4650", "Statement Period: 01/09/2026 - 30/09/2026",
                "Statement Generation Date: 01/10/2026", "Total Amount Due: Rs. 1.00", "Minimum Amount Due: Rs. 1.00"));
        assertThat(r.content().period()).isEqualTo(YearMonth.of(2026, 9));
    }

    // ------------------------------------------------------------------ numbers

    @Test
    void addOnAndPrimaryAreSeparate() throws Exception {
        List<String> l = new ArrayList<>(cardLines(CARDS.get(0), DATE_LINES[0]));
        l.add(6, "Add-on Card: XXXX XXXX XXXX 8735");
        l.add(7, "Supplementary Card No: 4000 XXXX XXXX 2468");
        StatementContent s = read(l).content();
        assertThat(s.last4()).isEqualTo("4381");
        assertThat(s.addOnLast4()).containsExactlyInAnyOrder("8735", "2468");
    }

    @Test
    void perCardSubtotalWithADifferentNumberIsAnAddOn() throws Exception {
        List<String> l = new ArrayList<>(cardLines(CARDS.get(1), DATE_LINES[0]));
        l.add("Transactions for card XXXX XXXX XXXX 7311 total 3,400.00");
        l.add("Marketing: get a card for your family");
        StatementContent s = read(l).content();
        assertThat(s.last4()).isEqualTo("0045");
        assertThat(s.addOnLast4()).containsExactly("7311");
    }

    @Test
    void addOnHeadingWithTheNumberOnTheNextLine() throws Exception {
        List<String> l = new ArrayList<>(cardLines(CARDS.get(5), DATE_LINES[0]));
        l.add("Add-on Card Details");
        l.add("Card Number XXXX XXXX XXXX 9021");
        StatementContent s = read(l).content();
        assertThat(s.last4()).isEqualTo("3318");
        assertThat(s.addOnLast4()).containsExactly("9021");
    }

    @Test
    void marketingForAddOnCardsHasNoNumbersAndNoAddOns() throws Exception {
        List<String> l = new ArrayList<>(cardLines(CARDS.get(0), DATE_LINES[0]));
        l.add("Apply for an Add-on Card for your family and earn bonus Reward Points. Call 1800 202 6161");
        assertThat(read(l).content().addOnLast4()).isEmpty();
    }

    @Test
    void twoPrimaryCardsInOneStatementLeaveTheNumberBlank() throws Exception {
        List<String> l = new ArrayList<>(List.of("Axis Bank Credit Card Statement", "Card No: 408791******9894", "Card No: 408791******1457",
                "Statement Date: 15/09/2026", "Total Amount Due: Rs. 100.00", "Minimum Amount Due: Rs. 10.00", "Credit Limit: Rs. 2,50,000.00"));
        StatementContent s = read(l).content();
        assertThat(s.bank()).isEqualTo("axis");
        assertThat(s.last4()).isNull();
        assertThat(s.addOnLast4()).isEmpty();
        assertThat(s.kind()).isEqualTo(Kind.CREDIT_CARD);
    }

    @Test
    void noNumberInTheHeaderLeavesLast4Blank() throws Exception {
        List<String> l = new ArrayList<>(List.of("ICICI Bank Credit Card Statement", "Statement Date: 15/09/2026", "Total Amount Due: Rs. 100.00",
                "Minimum Amount Due: Rs. 10.00", "Credit Limit: Rs. 2,50,000.00"));
        assertThat(read(l).content().last4()).isNull();
    }

    @Test
    void aYearNextToEndingIsNotACardNumber() throws Exception {
        List<String> l = new ArrayList<>(List.of("ICICI Bank Credit Card Statement", "Statement for the year ending 2026", "Card ending 2026 renewal reminder",
                "Statement Date: 15/09/2026", "Total Amount Due: Rs. 100.00", "Minimum Amount Due: Rs. 10.00"));
        assertThat(read(l).content().last4()).isNull();
    }

    @Test
    void binGroupOfAMaskedNumberIsNotASecondCard() throws Exception {
        StatementContent s = read(List.of("HDFC Bank Credit Card Statement", "Card No: 4893 21XX XXXX 2496", "Statement Date:15/09/2020",
                "Total Dues Rs. 100.00", "Minimum Amount Due Rs. 10.00")).content();
        assertThat(s.last4()).isEqualTo("2496");
    }

    @Test
    void savingsAccountNumberIsTheAccountsLast4() throws Exception {
        StatementContent s = read(acctLines(ACCOUNTS.get(0), "01/09/2026 to 30/09/2026")).content();
        assertThat(s.last4()).isEqualTo("4821");
    }

    // ------------------------------------------------------------------ product, closure

    @Test
    void productPhraseComesFromTheTitleOnly() throws Exception {
        StatementContent s = read(cardLines(CARDS.get(0), DATE_LINES[0])).content();
        assertThat(s.variant()).isEqualTo("Regalia Gold");
        assertThat(s.rawProduct()).contains("Regalia Gold");
        List<String> l = new ArrayList<>(cardLines(CARDS.get(1), DATE_LINES[0]));
        for (int i = 0; i < 40; i++) l.add("row " + i);
        l.add("Upgrade to your ICICI Bank Sapphiro Credit Card today");
        assertThat(read(l).content().variant()).isNull();
    }

    @Test
    void unknownProductKeepsTheRawPhraseOnly() throws Exception {
        StatementContent s = read(List.of("Axis Bank Zephyr Elite Credit Card Statement", "Card No: 408791******9894", "Statement Date: 15/09/2026",
                "Total Amount Due: Rs. 100.00", "Minimum Amount Due: Rs. 10.00")).content();
        assertThat(s.variant()).isNull();
        assertThat(s.rawProduct()).contains("Zephyr Elite");
    }

    @Test
    void closureNeedsAnExplicitHeaderStatement() throws Exception {
        List<String> closed = new ArrayList<>(cardLines(CARDS.get(0), DATE_LINES[0]));
        closed.add(2, "Card Status: Closed");
        assertThat(read(closed).content().mentionsClosure()).isTrue();
        List<String> terms = new ArrayList<>(cardLines(CARDS.get(0), DATE_LINES[0]));
        terms.add("If you do not pay, your card will be closed and the account may be closed by the bank");
        assertThat(read(terms).content().mentionsClosure()).isFalse();
        assertThat(read(cardLines(CARDS.get(0), DATE_LINES[0])).content().mentionsClosure()).isFalse();
    }

    @Test
    void partnerBrandHeaderBeatsItsIssuerBank() throws Exception {
        Result r = read(List.of("OneCard Statement", "Issued by Federal Bank", "Card Number: 5409 XXXX XXXX 2276", "Statement Date: 15/09/2026",
                "Total Amount Due: Rs. 100.00", "Minimum Amount Due: Rs. 10.00", "Credit Limit: Rs. 1,00,000.00", "www.getonecard.app", "www.federalbank.co.in"));
        assertThat(r.content().bank()).isEqualTo("onecard");
    }

    @Test
    void bankHintIsKeptButAContradictionIsFlagged() throws Exception {
        Result r = StatementPdfReader.read(pdf(cardLines(CARDS.get(1), DATE_LINES[0])), "PDF_MAIL", "hdfc", null);
        assertThat(r.content().bank()).isEqualTo("hdfc");
        assertThat(r.flags()).contains("bank:hint-differs");
    }

    // ------------------------------------------------------------------ the limit is the total / sanctioned one only

    private static List<String> limitDoc(String... limitLines) {
        List<String> l = new ArrayList<>(List.of("Standard Chartered Bank Credit Card Statement", "Card Number: 5290 XXXX XXXX 3406", "Statement Date: 15/09/2026",
                "Total Amount Due: Rs. 5,000.00", "Minimum Amount Due: Rs. 250.00"));
        l.addAll(List.of(limitLines));
        return l;
    }

    @Test
    void limitIsTheTotalCreditLimitNotTheAvailableOrCashOneInEveryWording() throws Exception {
        List<List<String>> docs = List.of(
                limitDoc("Credit Limit (Including cash) : Rs.3,00,000.00", "Available Credit Limit (Including cash) : Rs.50,000.00"),
                limitDoc("Available Credit Limit (Including cash) : Rs.50,000.00", "Credit Limit (Including cash) : Rs.3,00,000.00"),
                limitDoc("Available Cash Limit: Rs. 20,000.00", "Total Credit Limit: Rs. 3,00,000.00", "Available Credit Limit: Rs. 50,000.00"),
                limitDoc("Available Credit Limit: Rs. 50,000.00", "Cash Limit: Rs. 60,000.00", "Credit Limit: Rs. 3,00,000.00"),
                limitDoc("Used Credit Limit: Rs. 40,000.00", "Credit Limit: Rs. 3,00,000.00"),
                limitDoc("Credit Limit Used: Rs. 40,000.00", "Credit Limit: Rs. 3,00,000.00"));
        for (List<String> d : docs) assertThat(read(d).content().limitBucket()).as(d.toString()).isEqualTo("3-5L");
    }

    @Test
    void onlyAnAvailableOrCashLimitGivesNoLimit() throws Exception {
        assertThat(read(limitDoc("Available Credit Limit (Including cash) : Rs.50,000.00")).content().limitBucket()).isNull();
        assertThat(read(limitDoc("Available Cash Limit: Rs. 20,000.00", "Cash Limit: Rs. 60,000.00")).content().limitBucket()).isNull();
    }

    @Test
    void aLimitColumnRowIgnoresAvailableAndCashColumns() throws Exception {
        Result r = read(List.of("Standard Chartered Bank Credit Card Statement", "Card Number: 5290 XXXX XXXX 3406", "Statement Date: 15/09/2026",
                "Total Amount Due: Rs. 5,000.00", "Minimum Amount Due: Rs. 250.00",
                "Credit Limit Available Credit Limit Available Cash Limit", "Rs. 3,00,000.00 Rs. 50,000.00 Rs. 20,000.00"));
        assertThat(r.content().limitBucket()).isEqualTo("3-5L");
    }
}
