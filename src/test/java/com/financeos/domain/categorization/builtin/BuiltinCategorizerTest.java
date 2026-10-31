package com.financeos.domain.categorization.builtin;

import com.financeos.gmail.extract.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All inputs are made up. */
class BuiltinCategorizerTest {

    private static BuiltinCategorizer categorizer;

    @BeforeAll
    static void load() {
        categorizer = new BuiltinCategorizer();
    }

    private static CategorizeInput in(String counterparty, Direction direction, String subject, String text, String mcc) {
        return new CategorizeInput(counterparty, direction, new BigDecimal("100.00"), "alerts@examplebank.test", subject, text, null, mcc);
    }

    private static CategorizeInput debit(String counterparty) {
        return in(counterparty, Direction.DEBIT, "Alert", "Rs 100 spent", null);
    }

    private static BuiltinMatch match(CategorizeInput in) {
        Optional<BuiltinMatch> m = categorizer.categorize(in);
        assertTrue(m.isPresent(), "expected a match");
        return m.get();
    }

    private static void assertMatch(String category, Step step, BuiltinMatch m) {
        assertEquals(step, m.step());
        assertEquals(category, m.category());
    }

    // LINK

    @Test
    void cardBillPaymentOnTheBankSideIsALink() {
        BuiltinMatch m = match(in("CRED Club", Direction.DEBIT, "Debit alert", "Rs 5000 debited", null));
        assertMatch(null, Step.LINK, m);
    }

    @Test
    void paymentReceivedOnACardIsALink() {
        BuiltinMatch m = match(in(null, Direction.CREDIT, "Payment received on your Example Bank Credit Card.",
                "We have received payment of INR 500.00 on your Example Bank Credit Card account", null));
        assertMatch(null, Step.LINK, m);
    }

    @Test
    void selfTransferAndWalletTopUpAreLinks() {
        assertMatch(null, Step.LINK, match(in("Asha Rao", Direction.CREDIT, "Credit Alert", "IMPS ref Asha Rao Self transfer", null)));
        assertMatch(null, Step.LINK, match(in(null, Direction.CREDIT, "Rs 500 added to your Pay Wallet.", "Money added to your Pay wallet", null)));
    }

    @Test
    void linkBeatsKeywordMerchantAndPeople() {
        // "salary" keyword, a merchant name and a person-like name are all present; the card-bill reading wins
        BuiltinMatch m = match(in("Netflix", Direction.DEBIT, "salary", "your credit card bill payment was successful", null));
        assertMatch(null, Step.LINK, m);
    }

    // KEYWORD

    @Test
    void keywordsDecideOnTheirOwn() {
        assertMatch("Salary", Step.KEYWORD, match(in("Acme Corp", Direction.CREDIT, "Credit alert", "Salary credit for October", null)));
        assertMatch("Interest", Step.KEYWORD, match(in(null, Direction.CREDIT, "Credit alert", "Interest credited to your account", null)));
        assertMatch("Dividends", Step.KEYWORD, match(in(null, Direction.CREDIT, "Credit alert", "Final dividend for FY credited", null)));
        assertMatch("Refunds", Step.KEYWORD, match(in(null, Direction.CREDIT, "Refund processed for your order", "", null)));
        assertMatch("Cashback", Step.KEYWORD, match(in(null, Direction.CREDIT, "Your cashback is here", "", null)));
        assertMatch("Cash", Step.KEYWORD, match(in("Main Road", Direction.DEBIT, "Debit alert", "Cash withdrawal at ATM", null)));
        assertMatch("Charges", Step.KEYWORD, match(in(null, Direction.DEBIT, "Debit alert", "Annual fee and GST on fee debited", null)));
        assertMatch("Tolls", Step.KEYWORD, match(in(null, Direction.DEBIT, "FASTag recharge", "", null)));
        assertMatch("EMI", Step.KEYWORD, match(in("Sample Bank Loan EMI", Direction.DEBIT, "Debit alert", "", null)));
    }

    @Test
    void directionGuardsKeywordRules() {
        // a salary rule only applies to money in
        assertTrue(categorizer.categorize(in("Acme", Direction.DEBIT, "Debit alert", "Salary credit for October", null)).isEmpty());
    }

    @Test
    void shopWordsCountOnlyInTheCounterparty() {
        assertMatch("Pharmacy", Step.KEYWORD, match(debit("City Medicals")));
        assertMatch("Dining", Step.KEYWORD, match(debit("Sunrise Bakery")));
        // the same word in footer text does not decide anything
        assertTrue(categorizer.categorize(in("Zyxq Wvut", Direction.DEBIT, "Alert", "visit our bakery and pharmacy", null)).isPresent());
        assertMatch("People", Step.PEOPLE, match(in("Zyxq Wvut", Direction.DEBIT, "Alert", "visit our bakery and pharmacy", null)));
    }

    @Test
    void keywordBeatsMerchant() {
        // a refund of a known merchant is a refund, not the merchant's category
        assertMatch("Refunds", Step.KEYWORD, match(in("Netflix", Direction.CREDIT, "Refund", "refund of your payment", null)));
    }

    // MERCHANT

    @Test
    void merchantsMatchAfterNormalising() {
        assertMatch("Takeout", Step.MERCHANT, match(debit("SWIGGY LIMITED")));
        assertMatch("Takeout", Step.MERCHANT, match(debit("UPI_Swiggy")));
        assertMatch("Cabs", Step.MERCHANT, match(debit("Uber India Systems Pvt Ltd")));
        assertMatch("Trains", Step.MERCHANT, match(debit("IRCTC")));
        assertMatch("Streaming", Step.MERCHANT, match(debit("NETFLIX.COM")));
        assertMatch("Investments", Step.MERCHANT, match(debit("Zerodha Broking Limited")));
        assertMatch("Clothing", Step.MERCHANT, match(debit("RAZ*Myntra")));
    }

    @Test
    void longestMerchantNameWins() {
        assertMatch("Groceries", Step.MERCHANT, match(debit("Swiggy Instamart")));
        assertMatch("Takeout", Step.MERCHANT, match(debit("Swiggy")));
    }

    @Test
    void truncatedAndRunTogetherNamesMatch() {
        assertMatch("Pharmacy", Step.MERCHANT, match(debit("APOLLO PHAR")));
        assertMatch("Software", Step.MERCHANT, match(debit("GOOGLEPLAY")));
    }

    @Test
    void upiHandlePrefixMatches() {
        BuiltinMatch m = match(debit("swiggy@icici"));
        assertMatch("Takeout", Step.MERCHANT, m);
        assertMatch("Takeout", Step.MERCHANT, match(debit("zomato-order@hdfcbank")));
        assertMatch("Investments", Step.MERCHANT, match(debit("zerodhabroking.brk@icici")));
        assertMatch("Trains", Step.MERCHANT, match(debit("irctcpgonline@ybl")));
    }

    @Test
    void merchantNameInTheEmailTextIsUsedWhenTheCounterpartyIsJunk() {
        BuiltinMatch m = match(in("confirm that your Credit card", Direction.DEBIT, "Alert",
                "Your card was used for INR 499.00 for payment to NETFLIX on 16 Aug 2026 at 20:05.", null));
        assertMatch("Streaming", Step.MERCHANT, m);
        assertEquals("netflix", m.key());
    }

    // MCC

    @Test
    void mccMapsCodesAndRanges() {
        assertMatch("Groceries", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "5411")));
        assertMatch("Fuel", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "5541")));
        assertMatch("Flights", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "3100")));
        assertMatch("Hotels", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "3700")));
    }

    @Test
    void unknownOrMalformedMccIsIgnored() {
        assertTrue(categorizer.categorize(in("Zyxq", Direction.DEBIT, "Alert", "", "0001")).isEmpty());
        assertTrue(categorizer.categorize(in("Zyxq", Direction.DEBIT, "Alert", "", "abc")).isEmpty());
        assertTrue(categorizer.categorize(in("Zyxq", Direction.DEBIT, "Alert", "", " ")).isEmpty());
    }

    @Test
    void merchantBeatsMcc() {
        assertMatch("Takeout", Step.MERCHANT, match(in("Swiggy", Direction.DEBIT, "Alert", "", "5411")));
    }

    // PEOPLE

    @Test
    void individualNamesArePeople() {
        BuiltinMatch m = match(debit("Ramesh Kumar"));
        assertMatch("People", Step.PEOPLE, m);
        assertMatch("People", Step.PEOPLE, match(debit("UPI_MR RAVI KUMAR SHARMA")));
        assertMatch("People", Step.PEOPLE, match(debit("Mrs. Sunita Devi")));
    }

    @Test
    void businessNamesAreNotPeople() {
        assertTrue(categorizer.categorize(debit("Kumar Traders")).isEmpty());
        assertTrue(categorizer.categorize(debit("Sharma General Store")).isEmpty());
        assertTrue(categorizer.categorize(debit("Ravi Enterprises")).isEmpty());
        assertTrue(categorizer.categorize(debit("Anand Kumar Pvt Ltd")).isEmpty());
    }

    @Test
    void namesOfTheWrongShapeAreNotPeople() {
        assertTrue(categorizer.categorize(debit("Madhav")).isEmpty());
        assertTrue(categorizer.categorize(debit("A B")).isEmpty());
        assertTrue(categorizer.categorize(debit("One Two Three Four Five")).isEmpty());
        assertTrue(categorizer.categorize(debit("Order 12345 Ref")).isEmpty());
        assertTrue(categorizer.categorize(debit("PAY*Zyxq Wvut")).isEmpty());
    }

    @Test
    void cutOffBusinessWordsAreNotPeople() {
        // some banks cut the counterparty at 11 characters: "MOONLIGHT HOT" is a hotel
        assertMatch("Hotels", Step.KEYWORD, match(new CategorizeInput("Moonlight Hotel", Direction.DEBIT, new BigDecimal("5000"), null, "Alert", "Rs 5000 spent", null, null)));
        assertTrue(categorizer.categorize(debit("MOONLIGHT HOT")).isEmpty());
    }

    @Test
    void mobileNumberHandlesArePeople() {
        BuiltinMatch m = match(debit("9876543210@ybl"));
        assertMatch("People", Step.PEOPLE, m);
        assertEquals("handle", m.key());
        assertMatch("People", Step.PEOPLE, match(in(null, Direction.DEBIT, "UPI txn",
                "Rs 190 is debited towards VPA *******0000@okicici (Zyxqa) on 20-10-26.", null)));
    }

    @Test
    void personalGooglePayHandleIsPeopleButBusinessHandlesAreNot() {
        assertMatch("People", Step.PEOPLE, match(debit("zyxqwvut12@okaxis")));
        assertTrue(categorizer.categorize(debit("paytmqr1abc2de@paytm")).isEmpty());
        assertTrue(categorizer.categorize(debit("zyxqmobilestore@okaxis")).isEmpty());
    }

    // order and fall-through

    @Test
    void unknownCounterpartyIsLeftToTheAi() {
        assertTrue(categorizer.categorize(debit("Zyxqwvut")).isEmpty());
        assertTrue(categorizer.categorize(new CategorizeInput(null, null, null, null, null, null, null, null)).isEmpty());
        assertTrue(categorizer.categorize(in("", Direction.DEBIT, "", "", null)).isEmpty());
    }

    @Test
    void linkHasNoCategory() {
        BuiltinMatch m = match(in("CRED", Direction.DEBIT, "", "", null));
        assertNull(m.category());
        assertEquals(Step.LINK, m.step());
    }

    // V2 rules

    private static CategorizeInput withText(String cp, Direction d, String text) {
        return new CategorizeInput(cp, d, new BigDecimal("100.00"), "alerts@examplebank.test", "Alert", text, null, null);
    }

    private static final String CARD_TEXT = "Dear Customer, Thank you for using your credit card no. XX1234 for INR 100 at ";

    @Test
    void cardSpendsAtNameLookingMerchantsAreNotPeople() {
        assertTrue(categorizer.categorize(withText("Zyxq Wvut", Direction.DEBIT, CARD_TEXT + "Zyxq Wvut on 01-01-2026")).isEmpty());
        assertMatch("People", Step.PEOPLE, match(withText("Zyxq Wvut", Direction.DEBIT, "Rs 100 debited via UPI to Zyxq Wvut")));
    }

    @Test
    void singleWordNamesArePeopleOnlyWhenTheMailSaysPerson() {
        assertMatch("People", Step.PEOPLE, match(withText("Zyxqwv", Direction.DEBIT, "debited UPI REF NO 1 P2P-Zyxqwv-UPI- HEAD OFFICE")));
        assertTrue(categorizer.categorize(withText("Zyxqwv", Direction.DEBIT, "debited via UPI to Zyxqwv")).isEmpty());
        assertMatch("People", Step.PEOPLE, match(withText("Mr Zyxqwv", Direction.CREDIT, "credited by VPA zy@okaxis Mr Zyxqwv")));
    }

    @Test
    void merchantQrAndBusinessHandlesAreNotPeople() {
        assertTrue(categorizer.categorize(debit("q123456789@ybl")).isEmpty());
        assertTrue(categorizer.categorize(debit("9876543210@okbizaxis")).isEmpty());
    }

    @Test
    void transfersToTheHolderOwnNameAreLinks() {
        String text = "Dear Zyxq Wvut A, Rs 500 has been debited to VPA zyxqwvut1-1@okhdfcbank on 01-01-26 via UPI";
        assertMatch(null, Step.LINK, match(withText("ZYXQ WVUT A", Direction.DEBIT, text)));
        assertMatch(null, Step.LINK, match(withText("A ZYXQ WVUT", Direction.CREDIT, text)));
        assertMatch(null, Step.LINK, match(withText("zyxqwvut1-1@okhdfcbank", Direction.DEBIT, text)));
        assertMatch(null, Step.LINK, match(new CategorizeInput("Zyxq Wvut", Direction.DEBIT, null, null, null, "Dear Customer, via UPI", null, null, "Zyxq Wvut")));
        assertMatch("People", Step.PEOPLE, match(withText("Bhavna Rao", Direction.DEBIT, text)));
    }

    @Test
    void amazonPayRewardCreditIsCashbackNotAWalletLoad() {
        assertMatch("Cashback", Step.KEYWORD, match(withText(null, Direction.CREDIT,
                "Thank you. Your reward points have been added to your Amazon Pay balance")));
        assertMatch(null, Step.LINK, match(withText(null, Direction.CREDIT, "Money added to your Amazon Pay balance")));
    }

    @Test
    void amazonCardSpendsAreShoppingAndRefundWordsOnDebitsAreNot() {
        assertMatch("Shopping", Step.MERCHANT, match(debit("Amazon")));
        assertMatch("Groceries", Step.KEYWORD, match(debit("IND*Amazon.in - Grocer")));
        assertMatch("Flights", Step.MERCHANT, match(debit("AMAZON PAY IN FLIGHTS")));
        assertMatch("Software", Step.KEYWORD, match(withText("Google Play recharge", Direction.DEBIT, "refundable once redeemed")));
    }

    @Test
    void cardAutopayToAMerchantIsNotACardBill() {
        BuiltinMatch m = match(withText("Amazon", Direction.DEBIT, "payment towards Amazon, with Standing Instruction ABC, through your Credit Card 0005"));
        assertEquals(Step.MERCHANT, m.step());
    }

    @Test
    void smallHotelBillsAreDiningAndBigOnesAreHotels() {
        assertMatch("Dining", Step.KEYWORD, match(new CategorizeInput("Hotel Zyxq", Direction.DEBIT, new BigDecimal("320"), null, null, "x", null, null)));
        assertMatch("Hotels", Step.KEYWORD, match(new CategorizeInput("Hotel Zyxq", Direction.DEBIT, new BigDecimal("5000"), null, null, "x", null, null)));
        assertMatch("Hotels", Step.KEYWORD, match(debit("Zyxq Residency")));
    }

    @Test
    void travelsAreBusesAndAcademiesAreCourses() {
        assertMatch("Buses", Step.KEYWORD, match(debit("Zyxq Travels")));
        assertMatch("Travel", Step.MERCHANT, match(debit("Akbar Travels")));
        assertMatch("Courses", Step.KEYWORD, match(debit("Zyxq Academy Private Limited")));
        assertMatch("Education", Step.KEYWORD, match(debit("Zyxq Public School")));
    }

    @Test
    void cutOffBrandFamiliesAreAmbiguousAndLeftToTheAi() {
        assertTrue(categorizer.categorize(withText("ADITYA BIRL", Direction.DEBIT, CARD_TEXT + "ADITYA BIRL")).isEmpty());
        assertMatch("Takeout", Step.MERCHANT, match(debit("Swiggy IN")));
    }

    @Test
    void creditsFromMerchantsAreLeftToTheAi() {
        assertTrue(categorizer.categorize(withText("Netflix", Direction.CREDIT, "credited")).isEmpty());
    }

    @Test
    void ownImpsMoveAndWalletCardLoadAreLinks() {
        assertMatch(null, Step.LINK, match(withText(null, Direction.DEBIT,
                "INR 400 has been debited from your account ending 0001 on 01-08-26 and credited to the account ending 0002 via IMPS")));
        assertMatch(null, Step.LINK, match(debit("MOBIKWIK")));
    }

    @Test
    void gamesAndCashbackVpaAndDirectTaxes() {
        assertMatch("Gaming", Step.KEYWORD, match(debit("Zyxq Games Hub")));
        assertMatch("Cashback", Step.KEYWORD, match(withText("Zyxq Tech", Direction.CREDIT, "credited by VPA zyxqcashback@axisbank")));
        assertMatch("Taxes", Step.KEYWORD, match(debit("Central Board of Direct Taxes")));
    }

    // V3 rules

    @Test
    void amazonPayPaidToSingleWordIsNotPeople() {
        assertTrue(categorizer.categorize(new CategorizeInput("Zyxqwv", Direction.DEBIT, new BigDecimal("50"), "no-reply@pay.test",
                "Rs.50.00 was paid to Zyxqwv", "Your payment was successful. UPI Reference ID 1", null, null)).isEmpty());
    }

    @Test
    void creditCardUpiPayeeIsNeverPeople() {
        String text = "Rs.100 has been debited from your RuPay Credit Card (ending 1111) Paid to Zyxq Wvut via UPI";
        assertEquals(Optional.empty(), categorizer.categorize(withText("Zyxq Wvut", Direction.DEBIT, text)));
        assertMatch(null, Step.LINK, match(withText("EXAMPLE BANK CREDIT CARD", Direction.DEBIT,
                "Rs.100 debited from account 0001 to VPA billdeskpg.examplecard@bank EXAMPLE BANK CREDIT CARD via UPI")));
    }

    @Test
    void prepaidPostpaidAndUpiReversalsAndAmazonWalletLoad() {
        assertMatch("Mobile", Step.KEYWORD, match(withText("Zyxq Prepaid", Direction.DEBIT, "Convenience fee Rs 0")));
        assertMatch("Refunds", Step.KEYWORD, match(withText("ZYXQ WVUT", Direction.CREDIT, "credited on account of REVERSAL UPI 123 REV P2A-ZYXQ WVUT")));
        assertMatch(null, Step.LINK, match(withText(null, Direction.CREDIT, "Hi A, You have added Rs 1000.00 successfully to your Amazon Pay Wallet")));
        assertMatch(null, Step.LINK, match(debit("IND*Amazon Gift Card")));
        assertMatch(null, Step.LINK, match(withText(null, Direction.DEBIT, "Rs.1000 has been debited from account **0001 to account **0002 on 02-10-24")));
    }

    @Test
    void airtelPaymentsAndRelianceRetailAreLeftToTheAi() {
        assertTrue(categorizer.categorize(withText("AIRTEL PAYM", Direction.DEBIT, CARD_TEXT + "AIRTEL PAYM")).isEmpty());
        assertTrue(categorizer.categorize(withText("Reliance Retail", Direction.DEBIT, CARD_TEXT + "Reliance Retail")).isEmpty());
        assertMatch("Shopping", Step.MERCHANT, match(debit("Lenskart")));
    }

    @Test
    void resortsAreHotelsAndTrailingHotelIsDiningWhenSmall() {
        assertMatch("Hotels", Step.KEYWORD, match(debit("Zyxq Resort and Spa")));
        assertMatch("Dining", Step.KEYWORD, match(debit("Zyxq Hotel")));
    }

    @Test
    void diningWordsOnTheCounterparty() {
        assertMatch("Dining", Step.KEYWORD, match(debit("Zyxq Foods Pvt Ltd")));
        assertMatch("Dining", Step.KEYWORD, match(debit("Zyxq Veg")));
        assertMatch("Dining", Step.KEYWORD, match(debit("Zyxq Vilas")));
        assertMatch("Groceries", Step.KEYWORD, match(debit("Zyxq Mandi")));
        assertMatch("People", Step.PEOPLE, match(debit("Mandy Zyxqwv")));
    }

    // review fixes

    private static void assertNotMerchant(CategorizeInput in) {
        Optional<BuiltinMatch> m = categorizer.categorize(in);
        assertTrue(m.isEmpty() || m.get().step() != Step.MERCHANT, "unexpected merchant match: " + m);
    }

    @Test
    void shortBrandNamesDoNotMatchInsideLongerWords() {
        assertNotMerchant(debit("Digital Payments"));
        assertNotMerchant(debit("Shellys Corner"));
        assertNotMerchant(debit("Steamers"));
        assertNotMerchant(debit("Kayaking Club"));
        assertNotMerchant(debit("Trident Group"));
        assertMatch("Insurance", Step.KEYWORD, match(debit("Digit Insurance")));
        assertMatch("Fuel", Step.MERCHANT, match(debit("Shell")));
    }

    @Test
    void goldWordsAreNotAutomaticallyJewellery() {
        assertMatch("Jewellery", Step.KEYWORD, match(debit("Zyxq Gold Palace")));
        assertMatch("Investments", Step.KEYWORD, match(debit("Sovereign Gold Bond")));
        assertMatch("Investments", Step.KEYWORD, match(debit("Digital Gold")));
        assertTrue(categorizer.categorize(debit("Zomato Gold")).isEmpty() || !"Jewellery".equals(categorizer.categorize(debit("Zomato Gold")).get().category()));
    }

    @Test
    void relativesAreNotSelfTransfers() {
        String text = "Dear Zyxq Wvut, Rs 500 has been debited via UPI";
        assertMatch("People", Step.PEOPLE, match(withText("K Wvut", Direction.DEBIT, text)));
        assertMatch("People", Step.PEOPLE, match(withText("Zyxq Wvut", Direction.DEBIT, "Hi Zyxq, Rs 500 debited via UPI")));
        assertMatch(null, Step.LINK, match(withText("Zyxq Wvut", Direction.DEBIT, text)));
    }

    @Test
    void hugeInputsDoNotOverflowTheStack() {
        String big = "Zyxq ".repeat(2000);
        assertTrue(categorizer.categorize(withText(big, Direction.DEBIT, big + " via UPI")).isEmpty());
        assertTrue(categorizer.categorize(withText("Zyxq Wvut", Direction.DEBIT, big)).isPresent());
    }

    @Test
    void footerWordsDoNotMakeACashbackOrRefund() {
        String footer = "Rs 500 credited by UPI. Offers: earn cashback on every spend. See our refund policy.";
        BuiltinMatch m = match(withText("Zyxq Wvut", Direction.CREDIT, footer));
        assertMatch("People", Step.PEOPLE, m);
        assertMatch("Cashback", Step.KEYWORD, match(withText(null, Direction.CREDIT, "Cashback of Rs 50 has been credited to your account")));
        assertMatch("Refunds", Step.KEYWORD, match(withText(null, Direction.CREDIT, "Your refund of Rs 80 has been processed")));
    }
}
