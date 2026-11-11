package com.financeos.sms.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One case per format of the format list, filled with invented values, plus each not-a-transaction class. */
class SmsReaderTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-10T06:00:00Z");
    private final SmsReader reader = new SmsReader();

    private SmsReading read(String sender, String body) {
        return reader.read(sender, body, RECEIVED);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private void expect(SmsReading r, String key, String amount, TransactionType dir, String ending, String ref, String balance,
                        String party, LocalDate date) {
        assertThat(r.status()).isEqualTo(SmsReadStatus.READ);
        assertThat(r.kind()).isEqualTo(SmsKind.TRANSACTION);
        assertThat(r.templateKey()).isEqualTo(key);
        assertThat(r.amount()).isEqualByComparingTo(amount);
        assertThat(r.direction()).isEqualTo(dir);
        assertThat(r.ending()).isEqualTo(ending);
        assertThat(r.refNo()).isEqualTo(ref);
        if (balance == null) {
            assertThat(r.balanceAfter()).isNull();
        } else {
            assertThat(r.balanceAfter()).isEqualByComparingTo(balance);
        }
        assertThat(r.counterparty()).isEqualTo(party);
        assertThat(r.txnDate()).isEqualTo(date);
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    // ---- HSBC bank account
    @Test
    void hsbcAccountDebit() {
        expect(read("VM-HSBCIN-S", "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been debited with INR 1,250.50- on 08NOV as per your request . Your avl Bal is 40,100.25 ."),
                "hsbc_acct_debit", "1250.50", TransactionType.DEBIT, "789", null, "40100.25", null, LocalDate.of(2026, 11, 8));
    }

    @Test
    void hsbcUpiDebit() {
        expect(read("HSBCIN", "INR 300.00 is paid from HSBC account XXXXXX4321 to Test Payee on 09-Nov-26 with ref 612345678901. If this is not done by you, call 1800000000 to report."),
                "hsbc_upi_debit", "300.00", TransactionType.DEBIT, "4321", "612345678901", null, "Test Payee", LocalDate.of(2026, 11, 9));
    }

    @Test
    void hsbcNeftDebit() {
        expect(read("HSBCIN", "HSBC: A/c 123-456***-789 is debited with INR 5,000.00- on 07NOV with UTR TESTN12345678 as NEFT via Online to Sample Bank A/c ***5566 Test Name . Avl Bal is INR 35,100.25 . Report fraud 1800000000/1800000001."),
                "hsbc_neft_debit", "5000.00", TransactionType.DEBIT, "789", "TESTN12345678", "35100.25", "Test Name", LocalDate.of(2026, 11, 7));
    }

    @Test
    void hsbcDebitCard() {
        expect(read("HSBCIN", "HSBC:Thank you for using HSBC Debit Card XXXXX1234 for INR 999 on 06NOV at Sample Store .Your available bal is INR 34,101.25 .To report fraudulent transaction call 1800000000 (local) Or 1800000001 (overseas)."),
                "hsbc_debit_card", "999", TransactionType.DEBIT, "1234", null, "34101.25", "Sample Store", LocalDate.of(2026, 11, 6));
    }

    @Test
    void hsbcUpiCredit() {
        expect(read("HSBCIN", "Your HSBC Acc XXXXXX4321 is credited for INR 2,000.00 on 05-Nov-26 from tester@examplebank. UPI Ref No 612345678902"),
                "hsbc_upi_credit", "2000.00", TransactionType.CREDIT, "4321", "612345678902", null, "tester@examplebank", LocalDate.of(2026, 11, 5));
    }

    @Test
    void hsbcNeftCredit() {
        expect(read("HSBCIN", "HSBC: A/c 123-456***-789 is credited with INR 10,000.00+ on 04NOV at 10:15 with UTR TESTN87654321 as NEFT from Sample Bank A/c ***7788 of Some Person . Your Avl Bal is INR 45,100.25 ."),
                "hsbc_neft_credit", "10000.00", TransactionType.CREDIT, "789", "TESTN87654321", "45100.25", "Some Person", LocalDate.of(2026, 11, 4));
    }

    @Test
    void hsbcImpsCredit() {
        expect(read("HSBCIN", "HSBC: Your A/c 123-456***-789 is credited with INR 750.00+ on 03NOV at 09:05 with UTR TESTI11223344 as IMPS from Sample Bank A/c of Another Person . Your Avl Bal is INR 45,850.25 ."),
                "hsbc_imps_credit", "750.00", TransactionType.CREDIT, "789", "TESTI11223344", "45850.25", "Another Person", LocalDate.of(2026, 11, 3));
    }

    // ---- HP Pay
    @Test
    void hpPay() {
        expect(read("HPPAYS", "Transaction of Rs.1,500.00 for purchase of Petrol is successful . Regards, HP PAY Team"),
                "hp_debit", "1500.00", TransactionType.DEBIT, null, null, null, "Petrol", TODAY);
        expect(read("HPPAYS", "Dear customer,Your Add Voucher request of Rs. 2,000 has been processed successfully and your current HP Pay wallet balance is 3,500.50. Regards ,HP Pay Team"),
                "hp_voucher_credit", "2000", TransactionType.CREDIT, null, null, "3500.50", null, TODAY);
        expect(read("HPPAYS", "Your Wallet Recharge through Credit Card via Online Bank Payment Gateway bearing Order ID ORD123456, initiated on 09/11/2026 at 10:20 was successful and your Wallet Account has been recharged with Rs. 1,000.00.Your current HP PAY balance is Rs. 4,500.50. Regard..."),
                "hp_recharge_credit", "1000.00", TransactionType.CREDIT, null, "ORD123456", "4500.50", null, LocalDate.of(2026, 11, 9));
    }

    // ---- Amazon Pay wallet
    @Test
    void amazonPayWallet() {
        expect(read("JUSPAY", "Your Apay Wallet balance is debited for INR 250.00. Reference Number is REF99887766. If not you? call 1800000000 - SMS via Juspay"),
                "apay_debit_ref", "250.00", TransactionType.DEBIT, null, "REF99887766", null, null, TODAY);
        expect(read("QCAMZN", "Payment of Rs 120.50 using Apay Balance successful at Test Merchant.Updated Balance: Rs 879.50 -Powered by Juspay"),
                "apay_debit_bal_a", "120.50", TransactionType.DEBIT, null, null, "879.50", "Test Merchant", TODAY);
        expect(read("JUSPAY", "Payment of Rs 80 using Apay balance is successful at Test Shop. Updated balance is Rs 799.50. If not u? call 1800000000 - SMS via Pine Labs"),
                "apay_debit_bal_b", "80", TransactionType.DEBIT, null, null, "799.50", "Test Shop", TODAY);
    }

    // ---- PhonePe gift card
    @Test
    void phonePeGiftCard() {
        expect(read("PHONPE", "You've paid Rs.450.00 via PhonePe gift card to Test Cafe on Nov 08, 2026 at 18:30. Not you? Call us on 1800000000. Remaining balance Rs.550.00."),
                "phonepe_gift_debit", "450.00", TransactionType.DEBIT, null, null, "550.00", "Test Cafe", LocalDate.of(2026, 11, 8));
    }

    // ---- CRED wallet
    @Test
    void credWallet() {
        expect(read("CREDWL", "Update from CRED wallet: You have successfully paid Rs.300.00 and your updated wallet balance is Rs.700.00. For any queries or support, call 1800000000."),
                "cred_paid_a", "300.00", TransactionType.DEBIT, null, null, "700.00", null, TODAY);
        expect(read("CREDWL", "Update from CRED wallet: Your payment of Rs.150, made with CRED wallet, was successful. Your updated CRED wallet balance is Rs.550. For any queries or support, call 1800000000."),
                "cred_paid_b", "150", TransactionType.DEBIT, null, null, "550", null, TODAY);
        expect(read("CREDWL", "Update from CRED wallet: Rs.1,000.00 has been successfully credited to your CRED wallet. Your updated CRED wallet balance is Rs.1,550.00. For any queries or support, call 1800000000."),
                "cred_credit", "1000.00", TransactionType.CREDIT, null, null, "1550.00", null, TODAY);
    }

    // ---- MobiKwik
    @Test
    void mobikwik() {
        expect(read("MOBIKW", "Rs.75.00 has been debited from your MobiKwik wallet. Remaining balance: Rs.425.00. Not you? Please report on help@example.test -MobiKwik"),
                "mobikwik_debit", "75.00", TransactionType.DEBIT, null, null, "425.00", null, TODAY);
    }

    // ---- PayZapp
    @Test
    void payZapp() {
        expect(read("PAYZAP", "ALERT: Rs. 60.00 has been debited from your PayZapp wallet 4455. Avl Bal: Rs. 940.00"),
                "payzapp_debit", "60.00", TransactionType.DEBIT, "4455", null, "940.00", null, TODAY);
        expect(read("PAYZAP", "UPDATE: You have received Rs.200.00 from PayZapp user Test Friend. Available Balance: Rs.1,140.00. Not you? Call 1800000000 to report"),
                "payzapp_received", "200.00", TransactionType.CREDIT, null, null, "1140.00", "Test Friend", TODAY);
        expect(read("PAYZAP", "UPDATE: You have transferred Rs.90.00 to PayZapp user Other Friend. Available Balance: Rs.1,050.00. Not you? Call 1800000000 to report"),
                "payzapp_transfer", "90.00", TransactionType.DEBIT, null, null, "1050.00", "Other Friend", TODAY);
    }

    // ---- Paytm
    @Test
    void paytm() {
        expect(read("IPAYTM", "Paid Rs.110.00 to Test Store from Paytm Balance. Updated Balance: Paytm Wallet- Rs 390.00,Gift Voucher- Rs 50.00. More Details: https://example.test/x"),
                "paytm_balance", "110.00", TransactionType.DEBIT, null, null, "390.00", "Test Store", TODAY);
        SmsReading voucherOnly = read("PYTMBK", "Paid Rs.20 to Test Store from Paytm Balance. Updated Balance: Gift Voucher- Rs 30.00. More Details: https://example.test/x");
        assertThat(voucherOnly.balanceAfter()).isEqualByComparingTo("30.00");
        SmsReading noBalance = read("PAYTMB", "Paid Rs.20 to Test Store from Paytm Balance. More Details: https://example.test/x");
        assertThat(noBalance.status()).isEqualTo(SmsReadStatus.READ);
        assertThat(noBalance.balanceAfter()).isNull();
        expect(read("PAYTMB", "Paid Rs. 45.00 to Test Kiosk from Paytm Payments Bank Wallet. View details https://example.test/y :PPBL"),
                "paytm_ppbl", "45.00", TransactionType.DEBIT, null, null, null, "Test Kiosk", TODAY);
        expect(read("IPAYTM", "Paid Rs. 33.00 to Test Kiosk with Paytm Ref: PTM445566. For more details, visit https://example.test/z"),
                "paytm_ref", "33.00", TransactionType.DEBIT, null, "PTM445566", null, "Test Kiosk", TODAY);
    }

    // ---- Pluxee and Sodexo
    @Test
    void pluxeeAndSodexo() {
        expect(read("PLUXEE", "Rs. 220.00 was spent from Meal Wallet linked to your Pluxee Card xx1122 on 8-11-2026 13:05 at Test Canteen. Txn no. TX778899. Avl bal is Rs. 1,780.00. Not you? Call 1800000000. Pluxee"),
                "pluxee_spent_a", "220.00", TransactionType.DEBIT, "1122", "TX778899", "1780.00", "Test Canteen", LocalDate.of(2026, 11, 8));
        expect(read("PLUXEE", "Rs. 95.00 spent from Pluxee Meal wallet, card no.xx1122, on 9-11-2026 12:40 at Test Bakery. Avl bal Rs.1,685.00. Not you call 1800000000"),
                "pluxee_spent_b", "95.00", TransactionType.DEBIT, "1122", null, "1685.00", "Test Bakery", LocalDate.of(2026, 11, 9));
        expect(read("PLUXEE", "Your Pluxee Card has been successfully credited with Rs.2,000.00 towards Meal Wallet on Mon Nov 09 2026 09:00. Your current Meal Wallet balance is Rs.3,685.00."),
                "pluxee_credit", "2000.00", TransactionType.CREDIT, null, null, "3685.00", null, LocalDate.of(2026, 11, 9));
        expect(read("SODEXO", "Rs.180.00 was spent from your Sodexo Meal A/c xx3344 on 05-11-2026 14:10 at Test Diner. Txn no. SX112233. Avl bal is Rs.820.00. Not you? Call 1800000000. Sodexo"),
                "sodexo_spent", "180.00", TransactionType.DEBIT, "3344", "SX112233", "820.00", "Test Diner", LocalDate.of(2026, 11, 5));
        expect(read("SODEXO", "Your Sodexo Card has been successfully loaded with Rs.1,500.00 towards Meal A/c on 09:30,04 Nov. Current Meal A/c bal is Rs.2,320.00"),
                "sodexo_credit", "1500.00", TransactionType.CREDIT, null, null, "2320.00", null, LocalDate.of(2026, 11, 4));
    }

    @Test
    void spacingAndCommasAreTolerated() {
        SmsReading r = read("MOBIKW", "Rs.1,234.5  has been   debited from your MobiKwik wallet.   Remaining balance:Rs.10,000. Not you?");
        assertThat(r.status()).isEqualTo(SmsReadStatus.READ);
        assertThat(r.amount()).isEqualByComparingTo("1234.50");
        assertThat(r.balanceAfter()).isEqualByComparingTo("10000");
    }

    // ---- the year rule
    @Test
    void dateWithoutYearTakesTheReceivedYearUnlessMoreThanADayAhead() {
        String body = "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been debited with INR 10.00- on %s as per your request . Your avl Bal is 90.00 .";
        // received 2027-01-02: 30DEC is the previous year
        SmsReading prev = reader.read("HSBCIN", body.formatted("30DEC"), Instant.parse("2027-01-02T06:00:00Z"));
        assertThat(prev.txnDate()).isEqualTo(LocalDate.of(2026, 12, 30));
        // one day ahead of the received date is still this year
        SmsReading tomorrow = reader.read("HSBCIN", body.formatted("03JAN"), Instant.parse("2027-01-02T06:00:00Z"));
        assertThat(tomorrow.txnDate()).isEqualTo(LocalDate.of(2027, 1, 3));
        SmsReading twoAhead = reader.read("HSBCIN", body.formatted("04JAN"), Instant.parse("2027-01-02T06:00:00Z"));
        assertThat(twoAhead.txnDate()).isEqualTo(LocalDate.of(2026, 1, 4));
    }

    // ---- not a transaction
    private void ignored(String sender, String body, SmsKind kind) {
        SmsReading r = read(sender, body);
        assertThat(r.status()).as(body).isEqualTo(SmsReadStatus.IGNORED);
        assertThat(r.kind()).as(body).isEqualTo(kind);
        assertThat(r.amount()).isNull();
    }

    @Test
    void notATransactionClasses() {
        ignored("HSBCIN", "Your OTP for Rs 500.00 payment is 123456", SmsKind.OTP);
        ignored("HSBCIN", "INR 500.00 transaction failed: incorrect PIN. Please retry.", SmsKind.FAILED);
        ignored("HDFCBK", "Rs 500.00 debit was declined at Test Store", SmsKind.FAILED);
        ignored("HDFCBK", "Rs 500.00 will be debited from your account on 12-11-2026", SmsKind.FUTURE);
        ignored("PHONPE", "test@examplebank has sent you an AutoPay request for Rs. 99.00. Approve in the app.", SmsKind.REQUEST);
        ignored("PHONPE", "Test Person has requested money from you on PhonePe.Rs.100 will be debited from your account on approving the request - https://example.test/r", SmsKind.FUTURE);
        ignored("PAYZAP", "HDFC Bank: You have received a UPI-Mandate request from Test Person for Rs 100. Approve in the app.", SmsKind.REQUEST);
        ignored("HDFCBK", "UPI mandate created for Rs 1 towards Test Biller", SmsKind.MANDATE);
        ignored("HDFCBK", "Your mandate for Test Biller has been revoked", SmsKind.MANDATE);
        ignored("HDFCBK", "Auto Pay activation for Test Biller successful. Max limit Rs 15,000", SmsKind.MANDATE);
        ignored("HSBCIN", "HSBC: Dear HSBC Customer, your NEFT transaction with reference number TESTN12345678 for INR 5,000.00 has been credited to the Sample Bank A/c XXXXXXXXXX5566 of Test Name on 07-11-2026 at 10:15 .", SmsKind.SENDER_SIDE_CONFIRMATION);
        ignored("HSBCIN", "HSBC: Dear HSBC Customer, your RTGS transaction with reference number TESTR12345678 for INR 5,00,000.00 has been credited to the Sample Bank A/c XXXXXXXXXX5566 of Test Name on 07-11-2026", SmsKind.SENDER_SIDE_CONFIRMATION);
        ignored("HDFCBK", "Your credit card statement of Rs 1,200.00 is generated. Total amount due Rs 1,200.00", SmsKind.STATEMENT_DUE);
        ignored("HDFCBK", "Outstanding amount Rs 2,300.00 due on 20-11-2026", SmsKind.STATEMENT_DUE);
        ignored("HDFCBK", "Pre-approved loan of Rs 5,00,000 for you. Apply now", SmsKind.OFFER);
        ignored("HDFCBK", "Your credit limit has been increased to Rs 1,00,000", SmsKind.OFFER);
        ignored("HDFCBK", "Refund of Rs 300.00 has been initiated to your account", SmsKind.REFUND_INITIATED);
    }

    @Test
    void perSenderNotATransactionRows() {
        ignored("HSBCIN", "You've added HSBC Bank Credit Card no XXXX1234 for UPI. Transaction limit is upto Rs.1,00,000", SmsKind.NOT_A_TRANSACTION);
        ignored("HSBCIN", "Your secure key has been activated on a new device", SmsKind.NOT_A_TRANSACTION);
        ignored("HPPAYS", "Dear Customer, Congratulations. You received cashback of 10 Happy coins worth Rs. 5.00 and same shall be credited shortly in your account.", SmsKind.FUTURE);
        ignored("CREDWL", "You've updated/added a beneficiary: 'Test Person' on your CRED wallet. Rs 100 limit applies", SmsKind.NOT_A_TRANSACTION);
        ignored("MOBIKW", "Payment of Rs.1,000.00 was successfully received for your Sample Bank credit card 4111XXXXXXXX1111 on 08-11-2026 10:00.", SmsKind.NOT_A_TRANSACTION);
        ignored("MOBIKW", "New login to your MobiKwik account. Rs 0 charged", SmsKind.NOT_A_TRANSACTION);
        ignored("IPAYTM", "Your Paytm KYC is pending. Wallet limit Rs 10,000 applies", SmsKind.NOT_A_TRANSACTION);
        ignored("PLUXEE", "Your Pluxee card ending 1122 will expire. Balance Rs 500 lapses", SmsKind.NOT_A_TRANSACTION);
        ignored("PHONPE", "UPI registration complete for test@examplebank. Rs 1 test", SmsKind.NOT_A_TRANSACTION);
        ignored("PAYZAP", "Your PayZapp wallet activation done. Rs 0 balance", SmsKind.NOT_A_TRANSACTION);
    }

    @Test
    void phonePeFailedPaymentIsNotATransaction() {
        ignored("PHONPE", "Payment of Rs.120.00 for Test Biller via PhonePe (Txn Id: T123456789) has failed. Amount will be refunded", SmsKind.FAILED);
    }

    // ---- generic reader
    @Test
    void hsbcCreditCardGoesToTheGenericReader() {
        SmsReading r = read("HSBCIN", "Your HSBC Credit Card XX4321 was used for INR 1,200.00 spent at Test Mart on 08NOV. Ref TXN123456");
        assertThat(r.status()).isEqualTo(SmsReadStatus.NEEDS_CONFIRM);
        assertThat(r.templateKey()).isNull();
        assertThat(r.amount()).isEqualByComparingTo("1200.00");
        assertThat(r.direction()).isEqualTo(TransactionType.DEBIT);
        assertThat(r.ending()).isEqualTo("4321");
        assertThat(r.refNo()).isEqualTo("TXN123456");
        assertThat(r.txnDate()).isEqualTo(TODAY);
    }

    @Test
    void genericCreditNeedsConfirmation() {
        SmsReading r = read("TESTBK", "Rs 500.00 credited to your account XX9876. Avl bal Rs 1,500.00");
        assertThat(r.status()).isEqualTo(SmsReadStatus.NEEDS_CONFIRM);
        assertThat(r.direction()).isEqualTo(TransactionType.CREDIT);
        assertThat(r.balanceAfter()).isEqualByComparingTo("1500.00");
    }

    @Test
    void whenBothKindsOfWordAppearTheFirstOneWins() {
        SmsReading r = read("TESTBK", "Rs 500.00 debited from XX1111 and credited to XX2222");
        assertThat(r.status()).isEqualTo(SmsReadStatus.NEEDS_CONFIRM);
        assertThat(r.direction()).isEqualTo(TransactionType.DEBIT);
        assertThat(read("TESTBK", "Rs 500.00 credited to XX2222 after it was debited from XX1111").direction()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    void anythingElseIsIgnored() {
        assertThat(read("TESTBK", "Hello, your account is active").status()).isEqualTo(SmsReadStatus.IGNORED);
        assertThat(read("TESTBK", "Rs 500.00 is the minimum balance").status()).isEqualTo(SmsReadStatus.IGNORED);
        assertThat(read("TESTBK", "Money was debited today").status()).isEqualTo(SmsReadStatus.IGNORED);
    }

    @Test
    void aTemplateDebitIsNotIgnoredBecauseOfAWordInTheMerchantName() {
        expect(read("HPPAYS", "Transaction of Rs.80.00 for purchase of Offer Mart is successful . Regards, HP PAY Team"),
                "hp_debit", "80.00", TransactionType.DEBIT, null, null, null, "Offer Mart", TODAY);
        expect(read("PLUXEE", "Rs. 60.00 spent from Pluxee Meal wallet, card no.xx1122, on 9-11-2026 12:40 at Statement Store. Avl bal Rs.1,000.00. Not you call 1800000000"),
                "pluxee_spent_b", "60.00", TransactionType.DEBIT, "1122", null, "1000.00", "Statement Store", LocalDate.of(2026, 11, 9));
        expect(read("MOBIKW", "Rs.40.00 has been debited from your MobiKwik wallet. Remaining balance: Rs.100.00. Not you? Please report on help@example.test -MobiKwik login"),
                "mobikwik_debit", "40.00", TransactionType.DEBIT, null, null, "100.00", null, TODAY);
        // the explicit classes still win over a template
        ignored("HPPAYS", "Transaction of Rs.80.00 for purchase of Test Store is successful but the payment failed", SmsKind.FAILED);
    }

    @Test
    void anAmountOrBalanceWithMoreThan13IntegerDigitsCouldNotBeRead() {
        assertThat(read("MOBIKW", "Rs.12345678901234.00 has been debited from your MobiKwik wallet. Remaining balance: Rs.10.00. Not you?").status())
                .isEqualTo(SmsReadStatus.COULD_NOT_READ);
        assertThat(read("MOBIKW", "Rs.10.00 has been debited from your MobiKwik wallet. Remaining balance: Rs.12345678901234.00. Not you?").status())
                .isEqualTo(SmsReadStatus.COULD_NOT_READ);
        assertThat(read("TESTBK", "Rs 12345678901234 debited from A/c XX1234").status()).isEqualTo(SmsReadStatus.COULD_NOT_READ);
        assertThat(read("MOBIKW", "Rs.1234567890123.00 has been debited from your MobiKwik wallet. Remaining balance: Rs.10.00. Not you?").status())
                .isEqualTo(SmsReadStatus.READ);
    }

    // ---- generic reader: direction, card "used", payments, credit balance

    private SmsReading generic(String sender, String body, TransactionType dir, String amount) {
        SmsReading r = read(sender, body);
        assertThat(r.status()).as(body).isEqualTo(SmsReadStatus.NEEDS_CONFIRM);
        assertThat(r.templateKey()).isNull();
        assertThat(r.amount()).isEqualByComparingTo(amount);
        assertThat(r.direction()).as(body).isEqualTo(dir);
        return r;
    }

    @Test
    void aDebitAndItsCreditLegInOneTextReadAsTheDebit() {
        SmsReading a = generic("EQUTAS", "Dear Customer, Your a/c no. XXXXXXXX4321 is debited for Rs.150.00 on 08-Nov-2026 10:11:12 and a/c XXXXXXXX8765 credited. (IMPS Ref no 612345678911).- Equitas Bank",
                TransactionType.DEBIT, "150.00");
        assertThat(a.ending()).isEqualTo("4321");
        assertThat(a.refNo()).isEqualTo("612345678911");
        SmsReading b = generic("EQUTAS", "UPDATE: A/c XX4321 debited for INR 75.50 on 08-11-26 & A/c xxxxxxxx8765 credited (IMPS Ref No.612345678912).Avl bal:INR 900.00.Not you?Call 1800000000",
                TransactionType.DEBIT, "75.50");
        assertThat(b.ending()).isEqualTo("4321");
        assertThat(b.balanceAfter()).isEqualByComparingTo("900.00");
    }

    @Test
    void receivedOrDepositedBeforeASenderWordReadsAsACredit() {
        SmsReading a = generic("EQUTAS", "INR 200.00 deposited to A/c XXXXXXXX4321 towards UPI REF NO 612345678913 P2P-Test Person-Sent from Test App- HEAD OFFICE Value 08-NOV-2026 . Clear Bal is INR 1,200.00, for any query call 1800000000",
                TransactionType.CREDIT, "200.00");
        assertThat(a.ending()).isEqualTo("4321");
        assertThat(a.balanceAfter()).isEqualByComparingTo("1200.00");
        SmsReading b = generic("FEDBNK", "Test, you've received INR 300.00 in your Account XXXXXXXX4321. Woohoo! It was sent by 8765 on November 8, 2026. -Federal Bank",
                TransactionType.CREDIT, "300.00");
        assertThat(b.ending()).isEqualTo("4321");
        generic("FEDBNK", "You received INR 310.00 in your Account XXXXXXXX4321. Sent by Test Person | Date: November 8, 2026 Mode: UPI tester@examplebank-Federal Bank",
                TransactionType.CREDIT, "310.00");
    }

    @Test
    void theSenderSideConfirmationGuardStillWins() {
        ignored("HSBCIN", "HSBC: Dear HSBC Customer, your NEFT transaction with reference number TESTN11111111 for INR 500.00 has been credited to the Sample Bank A/c XXXXXXXXXX8765 of Test Name on 08-11-2026 at 10:00 .",
                SmsKind.SENDER_SIDE_CONFIRMATION);
        ignored("TESTBK", "Your NEFT for INR 500.00 has been credited to the Sample Bank A/c XXXXXXXXXX8765 of Test Name", SmsKind.SENDER_SIDE_CONFIRMATION);
    }

    @Test
    void aCardUsedIsADebitOfTheSpendNeverTheLimitOrTheDue() {
        SmsReading a = generic("HSBCIN", "HSBC Credit Card xx4321 used at Test Merchant for INR 450.00 on 07/11/26. Avl limit INR 90,000.00; due INR 1,250.00. Call 1800000000 to report fraud.",
                TransactionType.DEBIT, "450.00");
        assertThat(a.ending()).isEqualTo("4321");
        assertThat(a.counterparty()).isEqualTo("Test Merchant");
        assertThat(a.txnDate()).isEqualTo(LocalDate.of(2026, 11, 7));
        SmsReading b = generic("HSBCIN", "HSBC creditcard xxxxx4321 used at Test Shop One for INR 99.00 on 06/11/26.Limit Rs 80,000.00 Due Rs 500.00.Report fraud on 1800000000",
                TransactionType.DEBIT, "99.00");
        assertThat(b.ending()).isEqualTo("4321");
        assertThat(b.counterparty()).isEqualTo("Test Shop One");
        assertThat(b.txnDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        SmsReading c = generic("HSBCIN", "HSBC Credit Card xx4321 used for INR 12.00 at Test Cafe on 05/11/26 without additional factor authentication. If not you, call 1800000000.",
                TransactionType.DEBIT, "12.00");
        assertThat(c.counterparty()).isEqualTo("Test Cafe");
        assertThat(c.txnDate()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void anImpsPaymentFromTheAccountIsADebit() {
        SmsReading r = generic("SLCBNK", "IMPS payment of Rs. 640.00 from A/c xx4321 done on 08-Nov-26 to Test Person is successful (Ref ID: 612345678914). Not you? Call 1800000000 - slice",
                TransactionType.DEBIT, "640.00");
        assertThat(r.ending()).isEqualTo("4321");
        assertThat(r.refNo()).isEqualTo("612345678914");
        assertThat(r.counterparty()).isEqualTo("Test Person");
        assertThat(r.txnDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void aCreditBalanceNoticeIsNotATransaction() {
        ignored("AXISBK", "You have a credit balance of INR 120.00/- on your Axis Bank Credit Card no. XX4321. Call 1800000000 to place your refund request.",
                SmsKind.NOT_A_TRANSACTION);
    }

    // ---- new templates

    @Test
    void hsbcUtrCreditWithDotTimeAndRtgsOrImps() {
        expect(read("HSBCIN", "HSBC: A/c 123-456***-789 is credited with INR 7,500.00+ on 06NOV at 11.22.33 with UTR TESTN22222222 as NEFT from SAMP A/c ***9911 of Test Sender . Your Avl Bal is INR 20,100.25 ."),
                "hsbc_utr_credit", "7500.00", TransactionType.CREDIT, "789", "TESTN22222222", "20100.25", "Test Sender", LocalDate.of(2026, 11, 6));
        expect(read("HSBCIN", "HSBC: A/c 123-456***-789 is credited with INR 9,000.00+ on 05NOV at 09.01.02 with UTR TESTR33333333 as RTGS from SAMP A/c ***9911 of Test Sender . Your Avl Bal is INR 29,100.25 ."),
                "hsbc_utr_credit", "9000.00", TransactionType.CREDIT, "789", "TESTR33333333", "29100.25", "Test Sender", LocalDate.of(2026, 11, 5));
        expect(read("HSBCIN", "HSBC: A/c 123-456***-789 is credited with INR 400.00+ on 04NOV at 10.00.00 with UTR TESTI44444444 as IMPS from SAMP A/c ***9911 of Test Sender . Your Avl Bal is INR 29,500.25 ."),
                "hsbc_utr_credit", "400.00", TransactionType.CREDIT, "789", "TESTI44444444", "29500.25", "Test Sender", LocalDate.of(2026, 11, 4));
    }

    @Test
    void hsbcUtrDebitCoversRtgsAndImpsAndKeepsTheNeftOne() {
        expect(read("HSBCIN", "HSBC: A/c 123-456***-789 is debited with INR 25,000.00- on 03NOV with UTR TESTR55555555 as RTGS via Online to SAMP A/c ***9911 Test Payee . Avl Bal is INR 4,100.25 . Report fraud 1800000000/1800000001"),
                "hsbc_neft_debit", "25000.00", TransactionType.DEBIT, "789", "TESTR55555555", "4100.25", "Test Payee", LocalDate.of(2026, 11, 3));
        expect(read("HSBCIN", "HSBC: A/c 123-456***-789 is debited with INR 600.00- on 02NOV with UTR TESTI66666666 as IMPS via Online to SAMP A/c ***9911 Test Payee . Avl Bal is INR 3,500.25 . Report fraud 1800000000/1800000001"),
                "hsbc_neft_debit", "600.00", TransactionType.DEBIT, "789", "TESTI66666666", "3500.25", "Test Payee", LocalDate.of(2026, 11, 2));
    }

    @Test
    void hsbcInterestCredit() {
        expect(read("HSBCIN", "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been credited with INR 31.40+ on 01NOV as CREDIT INTEREST . Your available Bal is 3,531.65 ."),
                "hsbc_interest_credit", "31.40", TransactionType.CREDIT, "789", null, "3531.65", "Credit interest", LocalDate.of(2026, 11, 1));
    }

    @Test
    void newPluxeeAndSodexoSpendShapes() {
        expect(read("PLUXEE", "Rs. 85.00 spent from Pluxee Meal wallet, card no.xx1122 on 07-11-2026 12:30:15 at Test Kitchen. Avl bal Rs.915.00. Not you call 1800000000"),
                "pluxee_spent_c", "85.00", TransactionType.DEBIT, "1122", null, "915.00", "Test Kitchen", LocalDate.of(2026, 11, 7));
        expect(read("PLUXEE", "Rs. 70.00 was spent from Meal Wallet linked to your Pluxee Card xx1122 on 06-11-2026 13:45:7 at Test Hall. Txn no. TX990011. Avl bal is Rs. 845.00. Not you? Call 1800000000. Pluxee"),
                "pluxee_spent_d", "70.00", TransactionType.DEBIT, "1122", "TX990011", "845.00", "Test Hall", LocalDate.of(2026, 11, 6));
        expect(read("SODEXO", "Rs.55.00 was spent from your Sodexo Meal A/c xx3344 on 5-11-2026 14:20:9 at Test Counter. Txn no. SX445566. Avl bal is Rs.765.00. Not you? Call 1800000000. Sodexo"),
                "sodexo_spent_b", "55.00", TransactionType.DEBIT, "3344", "SX445566", "765.00", "Test Counter", LocalDate.of(2026, 11, 5));
    }

    // ---- certainty of the direction, and which figure is the payment

    @Test
    void unsettledOrMixedWordingMakesTheDirectionUncertain() {
        for (String body : List.of(
                "Rs 500.00 for your purchase at Test Store has been refunded to card XX4321",
                "Txn of Rs 500.00 debited from A/c XX4321 has been reversed and credited back",
                "Rs 300.00 sent by Test Person credited to A/c XX4321",
                "We have received your request. Rs 500 would be debited from A/c XX4321")) {
            assertThat(SmsReader.directionCertain(body)).as(body).isFalse();
            assertThat(reader.read("TESTBK", body, RECEIVED).status()).as(body).isEqualTo(SmsReadStatus.NEEDS_CONFIRM);
            assertThat(reader.read("TESTBK", body, RECEIVED).directionCertain()).as(body).isFalse();
        }
    }

    @Test
    void plainOrStronglyWordedTextsKeepACertainDirection() {
        assertThat(SmsReader.directionCertain("Rs 500.00 debited from A/c XX4321 and credited to Test Store")).isTrue();
        assertThat(SmsReader.directionCertain("Rs 500.00 credited to A/c XX4321")).isTrue();
        assertThat(SmsReader.directionCertain("Rs 99.00 spent at Test Mart")).isTrue();
        assertThat(reader.read("TESTBK", "Rs 500.00 debited from A/c XX4321 and credited to Test Store", RECEIVED).directionCertain()).isTrue();
        // templates are never uncertain
        assertThat(read("CREDWL", "Update from CRED wallet: You have successfully paid Rs.100.00 and your updated wallet balance is Rs.900.00. For any queries or support, call 1800000000.")
                .directionCertain()).isTrue();
    }

    @Test
    void theCardSpendIsTheAmountNotALimitThatComesFirst() {
        SmsReading r = generic("TESTBK", "Rs 90,000 limit available. Test Bank Credit Card xx4321 used at Test Merchant for INR 450.00 on 07/11/26",
                TransactionType.DEBIT, "450.00");
        assertThat(r.counterparty()).isEqualTo("Test Merchant");
        assertThat(r.txnDate()).isEqualTo(LocalDate.of(2026, 11, 7));
    }

    @Test
    void aFigureIntroducedByALimitBalanceOrDueIsNeverThePayment() {
        generic("TESTBK", "Avl limit INR 9,000.00. Rs 450.00 debited from XX4321", TransactionType.DEBIT, "450.00");
        generic("TESTBK", "Due Rs 1,200.00 and Rs 75.00 debited from A/c XX4321", TransactionType.DEBIT, "75.00");
        // only a limit figure: nothing to read
        assertThat(read("TESTBK", "Minimum due Rs 500.00 debited soon").status()).isEqualTo(SmsReadStatus.IGNORED);
    }

    @Test
    void aLimitSummaryIsNotATransactionReading() {
        SmsReading r = read("TESTBK", "Total card limit Rs 1,00,000, amount used Rs 20,000, available Rs 80,000");
        // no debit or credit word: the reader's existing rule for a text that is not a payment
        assertThat(r.status()).isEqualTo(SmsReadStatus.IGNORED);
        assertThat(r.kind()).isEqualTo(SmsKind.UNKNOWN);
    }

    @Test
    void aKeywordInsideAnotherWordDoesNotHideTheAmount() {
        generic("TESTBK", "Paid at Global Rs 500.00 from A/c XX4321, debited", TransactionType.DEBIT, "500.00");
        generic("TESTBK", "Rs 500.00 debited from A/c XX4321. Avl bal Rs 2,000.00", TransactionType.DEBIT, "500.00");
        generic("TESTBK", "Payment of Rs 500.00 received towards your card due", TransactionType.CREDIT, "500.00");
    }
}
