package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.SyntheticMailbox.BankSpec;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.financeos.gmail.accountdetect.SyntheticMailbox.MONTHS;
import static org.assertj.core.api.Assertions.assertThat;

/** A loan document (own heading or loan number) is no card or account statement; a card statement with an EMI section stays one. */
class LoanDocumentTest {

    private static final String DEBIT_CARD_EMI = String.join("\n",
            "3 0 20/03/2019DEBIT CARD EMI5/04/2019   4,133.164,133.1623, 813,55219,548   4,133.16 4,133.16 4,133.16 0.000.000.000.00 .004,133.160 0 0 0 0 00000360825XXXX7708",
            "Name : SAMPLE NAME A                           X",
            "000DEBIT CARD EMI",
            "Email : SAMPLE@EXAMPLE.COM",
            "Address : 1 SAMPLE STREET",
            "SAMPLE AREA",
            "CHENNAI-600000 TN",
            "00",
            "Statement Date:20/03/2019     Loan No: 0036 0825 XXXX 7708",
            "Payment Due Date Total Dues Loan Amount",
            "05/04/2019 4,133.16 23,681",
            "Account Summary",
            "Opening",
            "Balance",
            "Payment/ ",
            "Credits",
            "Purchase/ ",
            "Debits",
            "Finance",
            "Charges",
            "Total Dues",
            "4,133.16 4,133.16 4,133.16 0.00 4,133.16",
            "IMPORTANT INFORMATION",
            "Transaction Information",
            "Date  Transaction Description Amount (in Rs.)",
            "05/03/2019 AUTOPAY THANK YOU (Ref# ST190650078000011277890) 4,133.16 Cr",
            "20/03/2019 EMI ON DC,INT NBR:02,00000000000442 (Ref# 09999999980320021700029) 264.85  ",
            "   20/03/2019 EMI ON DC,PRIN NB:02,00000000000442 (Ref# 09999999980320021700011) 3,868.31  ",
            "Terms and Condition Customer Rights Policy",
            "For HDFC Bank",
            "DEBIT CARD EMI Loan Statement",
            "HDFC Bank GSTIN : 33AAACH2702H1Z7 HSN Code - 9971",
            " Page 1 of 1");

    private static final String CARD_WITH_EMI_SECTION = String.join("\n",
            "HDFC Bank Regalia Gold Credit Card Statement",
            "Card Member: SAMPLE NAME A",
            "Card No: 4893 21XX XXXX 4721",
            "Statement Date: 12/06/2025",
            "Credit Limit: Rs. 4,50,000.00",
            "Total Amount Due: Rs. 12,345.00   Minimum Amount Due: Rs. 620.00",
            "Payment Due Date: 02/07/2025",
            "SmartEMI Loan Summary",
            "Loan No: 0036 0825 XXXX 5512",
            "Outstanding principal 23,681.00   Instalment 4,133.16");

    private static StatementContent read(String text, Set<String> flags) {
        return StatementPdfReader.fromText(text, "PDF_MAIL", "hdfc", YearMonth.of(2019, 3), flags);
    }

    @Test
    void aDebitCardEmiLoanStatementIsNoCardAndNamesNoNumber() {
        Set<String> flags = new TreeSet<>();
        StatementContent c = read(DEBIT_CARD_EMI, flags);

        assertThat(c.kind()).isNull();
        assertThat(c.last4()).isNull();
        assertThat(flags).contains("kind:loan");
    }

    @Test
    void aLoanStatementHeadingAloneMakesALoanEvenWithCardLikeStructure() {
        String text = String.join("\n", "Loan Account Statement", "Statement Date: 12/06/2025", "Total Amount Due: Rs. 100.00",
                "Minimum Amount Due: Rs. 10.00", "Payment Due Date: 02/07/2025", "Account No: XXXX XXXX 3344");
        Set<String> flags = new TreeSet<>();
        StatementContent c = read(text, flags);

        assertThat(c.kind()).isNull();
        assertThat(c.last4()).isNull();
        assertThat(flags).contains("kind:loan");
    }

    @Test
    void aCardStatementWithAnEmiSectionStaysACardWithItsOwnNumber() {
        Set<String> flags = new TreeSet<>();
        StatementContent c = read(CARD_WITH_EMI_SECTION, flags);

        assertThat(c.kind()).isEqualTo(Kind.CREDIT_CARD);
        assertThat(c.last4()).isEqualTo("4721");
        assertThat(flags).doesNotContain("kind:loan");
    }

    @Test
    void aLoanDocumentContributesNoEntityAndNoStatementHeaderExemption() {
        // a card number only in a counterparty slot is dropped; a CREDIT_CARD statement header would exempt it (StatementHeaderTest),
        // but a loan document's statement has no kind, so it is only a NOT_STATEMENT link
        SyntheticMailbox mb = new SyntheticMailbox(402);
        BankSpec hdfc = mb.bank("hdfc", 0, false, false, false);
        mb.accountMails(hdfc, "3301", null, false);
        for (int m = 0; m < MONTHS; m++) {
            mb.alert(hdfc.domain(), mb.day(m), "Funds transfer alert", "INR 12000.00 debited from your A/c XX3301 and credited to A/c XX7708"
                    + " of beneficiary SAMPLE PAYEE via NEFT. UTR 4410" + (100000000000L + m) + ".");
        }
        YearMonth ym = YearMonth.of(2019, 3);
        mb.statements.add(new StatementContent("PDF_MAIL", "hdfc", null, null, ym, null, null, List.of(), false, ym.atDay(20)));

        Detection d = mb.detect(402);

        assertThat(d.find("hdfc", "7708")).isNull();
        assertThat(d.statementLinks).anyMatch(l -> l.bank().equals("hdfc") && l.last4() == null);
    }

    @Test
    void aCardStatementThatOnlyMentionsALoanStatementOrOffersLoansStaysACard() {
        String text = CARD_WITH_EMI_SECTION + "\nFor charges, please refer to your Loan Statement for details.\nInsta Loan on your card\nJumbo Loan offer";
        Set<String> flags = new TreeSet<>();
        StatementContent c = read(text, flags);

        assertThat(c.kind()).isEqualTo(Kind.CREDIT_CARD);
        assertThat(c.last4()).isEqualTo("4721");
        assertThat(flags).doesNotContain("kind:loan");
    }

    @Test
    void aSavingsStatementWithAnEmiDebitAndALinkedLoanAccountMentionStaysAnAccount() {
        String text = String.join("\n", "HDFC Bank Savings Account Statement", "Account No: 50100012345678", "IFSC: HDFC0000123", "Branch: SAMPLE",
                "Statement Date: 30/06/2025", "Opening Balance 10,000.00", "Closing Balance 8,000.00", "Withdrawals Deposits Balance",
                "15/06/2025 EMI DEBIT LOAN A/C LINKED 4,133.16 8,000.00", "Your linked Loan Account is shown in the Loan Statement.");
        Set<String> flags = new TreeSet<>();
        StatementContent c = read(text, flags);

        assertThat(c.kind()).isEqualTo(Kind.ACCOUNT);
        assertThat(flags).doesNotContain("kind:loan");
    }

    @Test
    void aLineHoldingACardNumberAndALoanNumberKeepsTheCardStatementACard() {
        String text = String.join("\n", "HDFC Bank Regalia Gold Credit Card Statement", "Card No XXXX XXXX XXXX 4721   Loan No 0036 0825 XXXX 5512",
                "Statement Date: 12/06/2025", "Credit Limit: Rs. 4,50,000.00", "Total Amount Due: Rs. 12,345.00   Minimum Amount Due: Rs. 620.00",
                "Payment Due Date: 02/07/2025");
        Set<String> flags = new TreeSet<>();
        StatementContent c = read(text, flags);

        assertThat(c.kind()).isEqualTo(Kind.CREDIT_CARD);
        assertThat(c.last4()).isEqualTo("4721");
        assertThat(flags).doesNotContain("kind:loan");
    }
}
