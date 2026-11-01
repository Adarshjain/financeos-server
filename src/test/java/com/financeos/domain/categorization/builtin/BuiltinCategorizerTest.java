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

    /** Empty when no step matched (the engine always returns a match; only its step tells). */
    private static Optional<BuiltinMatch> categorize(CategorizeInput in) {
        BuiltinMatch m = categorizer.categorize(in);
        return m.step() == null ? Optional.empty() : Optional.of(m);
    }

    private static BuiltinMatch match(CategorizeInput in) {
        Optional<BuiltinMatch> m = categorize(in);
        assertTrue(m.isPresent(), "expected a match");
        return m.get();
    }

    /** The most specific key: the sub-category when there is one, else the category. */
    private static String keyOf(BuiltinMatch m) {
        return m.subcategoryKey() != null ? m.subcategoryKey() : m.categoryKey();
    }

    /** {@code category} is a taxonomy key, null for "no category", or "PERSON" for a payment to an individual. */
    private static void assertMatch(String category, Step step, BuiltinMatch m) {
        assertEquals(step, m.step());
        if ("PERSON".equals(category)) {
            assertTrue(m.person());
            assertNull(m.categoryKey());
        } else {
            assertEquals(category, keyOf(m));
            assertEquals(false, m.person());
        }
    }

    // LINK

    @Test
    void cardBillPaymentOnTheBankSideIsALink() {
        BuiltinMatch m = match(in("CRED Club", Direction.DEBIT, "Debit alert", "Rs 5000 debited", null));
        assertMatch("card_bill", Step.LINK, m);
    }

    @Test
    void paymentReceivedOnACardIsALink() {
        BuiltinMatch m = match(in(null, Direction.CREDIT, "Payment received on your Example Bank Credit Card.",
                "We have received payment of INR 500.00 on your Example Bank Credit Card account", null));
        assertMatch("card_bill", Step.LINK, m);
    }

    @Test
    void selfTransferAndWalletTopUpAreLinks() {
        assertMatch("self_transfer", Step.LINK, match(in("Asha Rao", Direction.CREDIT, "Credit Alert", "IMPS ref Asha Rao Self transfer", null)));
        assertMatch(null, Step.LINK, match(in(null, Direction.CREDIT, "Rs 500 added to your Pay Wallet.", "Money added to your Pay wallet", null)));
    }

    @Test
    void linkBeatsKeywordMerchantAndPeople() {
        // "salary" keyword, a merchant name and a person-like name are all present; the card-bill reading wins
        BuiltinMatch m = match(in("Netflix", Direction.DEBIT, "salary", "your credit card bill payment was successful", null));
        assertMatch("card_bill", Step.LINK, m);
    }

    // KEYWORD

    @Test
    void keywordsDecideOnTheirOwn() {
        assertMatch("salary.salary", Step.KEYWORD, match(in("Acme Corp", Direction.CREDIT, "Credit alert", "Salary credit for October", null)));
        assertMatch("interest", Step.KEYWORD, match(in(null, Direction.CREDIT, "Credit alert", "Interest credited to your account", null)));
        assertMatch("dividends", Step.KEYWORD, match(in(null, Direction.CREDIT, "Credit alert", "Final dividend for FY credited", null)));
        assertMatch("refund", Step.KEYWORD, match(in(null, Direction.CREDIT, "Refund processed for your order", "", null)));
        assertMatch("cashback", Step.KEYWORD, match(in(null, Direction.CREDIT, "Your cashback is here", "", null)));
        assertMatch("cash", Step.KEYWORD, match(in("Main Road", Direction.DEBIT, "Debit alert", "Cash withdrawal at ATM", null)));
        assertMatch("fees", Step.KEYWORD, match(in(null, Direction.DEBIT, "Debit alert", "Annual fee and GST on fee debited", null)));
        assertMatch("transport.tolls", Step.KEYWORD, match(in(null, Direction.DEBIT, "FASTag recharge", "", null)));
        assertMatch("emi", Step.KEYWORD, match(in("Sample Bank Loan EMI", Direction.DEBIT, "Debit alert", "", null)));
    }

    @Test
    void directionGuardsKeywordRules() {
        // a salary rule only applies to money in
        assertTrue(categorize(in("Acme", Direction.DEBIT, "Debit alert", "Salary credit for October", null)).isEmpty());
    }

    @Test
    void shopWordsCountOnlyInTheCounterparty() {
        assertMatch("health.medicines", Step.KEYWORD, match(debit("City Medicals")));
        assertMatch("food.dining", Step.KEYWORD, match(debit("Sunrise Bakery")));
        // the same word in footer text does not decide anything
        assertTrue(categorize(in("Zyxq Wvut", Direction.DEBIT, "Alert", "visit our bakery and pharmacy", null)).isPresent());
        assertMatch("PERSON", Step.PEOPLE, match(in("Zyxq Wvut", Direction.DEBIT, "Alert", "visit our bakery and pharmacy", null)));
    }

    @Test
    void keywordBeatsMerchant() {
        // a refund of a known merchant is a refund, not the merchant's category
        assertMatch("refund", Step.KEYWORD, match(in("Netflix", Direction.CREDIT, "Refund", "refund of your payment", null)));
    }

    // MERCHANT

    @Test
    void merchantsMatchAfterNormalising() {
        assertMatch("food.delivery", Step.MERCHANT, match(debit("SWIGGY LIMITED")));
        assertMatch("food.delivery", Step.MERCHANT, match(debit("UPI_Swiggy")));
        assertMatch("transport.cabs", Step.MERCHANT, match(debit("Uber India Systems Pvt Ltd")));
        assertMatch("travel.trains", Step.MERCHANT, match(debit("IRCTC")));
        assertMatch("subscriptions.streaming", Step.MERCHANT, match(debit("NETFLIX.COM")));
        assertMatch("investments", Step.MERCHANT, match(debit("Zerodha Broking Limited")));
        assertMatch("shopping.clothes", Step.MERCHANT, match(debit("RAZ*Myntra")));
    }

    @Test
    void longestMerchantNameWins() {
        assertMatch("groceries", Step.MERCHANT, match(debit("Swiggy Instamart")));
        assertMatch("food.delivery", Step.MERCHANT, match(debit("Swiggy")));
    }

    @Test
    void truncatedAndRunTogetherNamesMatch() {
        assertMatch("health.medicines", Step.MERCHANT, match(debit("APOLLO PHAR")));
        assertMatch("subscriptions.software", Step.MERCHANT, match(debit("GOOGLEPLAY")));
    }

    @Test
    void upiHandlePrefixMatches() {
        BuiltinMatch m = match(debit("swiggy@icici"));
        assertMatch("food.delivery", Step.MERCHANT, m);
        assertMatch("food.delivery", Step.MERCHANT, match(debit("zomato-order@hdfcbank")));
        assertMatch("investments", Step.MERCHANT, match(debit("zerodhabroking.brk@icici")));
        assertMatch("travel.trains", Step.MERCHANT, match(debit("irctcpgonline@ybl")));
    }

    @Test
    void merchantNameInTheEmailTextIsUsedWhenTheCounterpartyIsJunk() {
        BuiltinMatch m = match(in("confirm that your Credit card", Direction.DEBIT, "Alert",
                "Your card was used for INR 499.00 for payment to NETFLIX on 16 Aug 2026 at 20:05.", null));
        assertMatch("subscriptions.streaming", Step.MERCHANT, m);
        assertEquals("netflix", m.key());
    }

    // MCC

    @Test
    void mccMapsCodesAndRanges() {
        assertMatch("groceries", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "5411")));
        assertMatch("transport.fuel", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "5541")));
        assertMatch("travel.flights", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "3100")));
        assertMatch("travel.hotels", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "3700")));
    }

    @Test
    void unknownOrMalformedMccIsIgnored() {
        assertTrue(categorize(in("Zyxq", Direction.DEBIT, "Alert", "", "0001")).isEmpty());
        assertTrue(categorize(in("Zyxq", Direction.DEBIT, "Alert", "", "abc")).isEmpty());
        assertTrue(categorize(in("Zyxq", Direction.DEBIT, "Alert", "", " ")).isEmpty());
    }

    @Test
    void merchantBeatsMcc() {
        assertMatch("food.delivery", Step.MERCHANT, match(in("Swiggy", Direction.DEBIT, "Alert", "", "5411")));
    }

    // PEOPLE

    @Test
    void individualNamesArePeople() {
        BuiltinMatch m = match(debit("Ramesh Kumar"));
        assertMatch("PERSON", Step.PEOPLE, m);
        assertMatch("PERSON", Step.PEOPLE, match(debit("UPI_MR RAVI KUMAR SHARMA")));
        assertMatch("PERSON", Step.PEOPLE, match(debit("Mrs. Sunita Devi")));
    }

    @Test
    void businessNamesAreNotPeople() {
        assertTrue(categorize(debit("Kumar Traders")).isEmpty());
        assertTrue(categorize(debit("Sharma General Store")).isEmpty());
        assertTrue(categorize(debit("Ravi Enterprises")).isEmpty());
        assertTrue(categorize(debit("Anand Kumar Pvt Ltd")).isEmpty());
    }

    @Test
    void namesOfTheWrongShapeAreNotPeople() {
        assertTrue(categorize(debit("Madhav")).isEmpty());
        assertTrue(categorize(debit("A B")).isEmpty());
        assertTrue(categorize(debit("One Two Three Four Five")).isEmpty());
        assertTrue(categorize(debit("Order 12345 Ref")).isEmpty());
        assertTrue(categorize(debit("PAY*Zyxq Wvut")).isEmpty());
    }

    @Test
    void cutOffBusinessWordsAreNotPeople() {
        // some banks cut the counterparty at 11 characters: "MOONLIGHT HOT" is a hotel
        assertMatch("travel.hotels", Step.KEYWORD, match(new CategorizeInput("Moonlight Hotel", Direction.DEBIT, new BigDecimal("5000"), null, "Alert", "Rs 5000 spent", null, null)));
        assertTrue(categorize(debit("MOONLIGHT HOT")).isEmpty());
    }

    @Test
    void mobileNumberHandlesArePeople() {
        BuiltinMatch m = match(debit("9876543210@ybl"));
        assertMatch("PERSON", Step.PEOPLE, m);
        assertEquals("handle", m.key());
        assertMatch("PERSON", Step.PEOPLE, match(in(null, Direction.DEBIT, "UPI txn",
                "Rs 190 is debited towards VPA *******0000@okicici (Zyxqa) on 20-10-26.", null)));
    }

    @Test
    void personalGooglePayHandleIsPeopleButBusinessHandlesAreNot() {
        assertMatch("PERSON", Step.PEOPLE, match(debit("zyxqwvut12@okaxis")));
        assertTrue(categorize(debit("paytmqr1abc2de@paytm")).isEmpty());
        assertTrue(categorize(debit("zyxqmobilestore@okaxis")).isEmpty());
    }

    // order and fall-through

    @Test
    void unknownCounterpartyIsLeftToTheAi() {
        assertTrue(categorize(debit("Zyxqwvut")).isEmpty());
        assertTrue(categorize(new CategorizeInput(null, null, null, null, null, null, null, null)).isEmpty());
        assertTrue(categorize(in("", Direction.DEBIT, "", "", null)).isEmpty());
    }

    @Test
    void linkRulesCarryTheirCategoryWhenItFitsTheDirection() {
        BuiltinMatch m = match(in("CRED", Direction.DEBIT, "", "", null));
        assertEquals("card_bill", m.categoryKey());
        assertEquals(Step.LINK, m.step());
        // top_up is out-side only: a wallet load credit is still a LINK but gets no category (and never a refund)
        BuiltinMatch credit = match(withText(null, Direction.CREDIT, "Money added to your Amazon Pay balance"));
        assertNull(credit.categoryKey());
        assertEquals(Step.LINK, credit.step());
        assertEquals("wallet-topup", credit.key());
    }

    @Test
    void cardCreditsReceivedStaysWithoutACategory() {
        BuiltinMatch m = match(in(null, Direction.CREDIT, "Received credits on your Example Bank Credit Card", "", null));
        assertEquals(Step.LINK, m.step());
        assertNull(m.categoryKey());
    }

    // V2 rules

    private static CategorizeInput withText(String cp, Direction d, String text) {
        return new CategorizeInput(cp, d, new BigDecimal("100.00"), "alerts@examplebank.test", "Alert", text, null, null);
    }

    private static final String CARD_TEXT = "Dear Customer, Thank you for using your credit card no. XX1234 for INR 100 at ";

    @Test
    void cardSpendsAtNameLookingMerchantsAreNotPeople() {
        assertTrue(categorize(withText("Zyxq Wvut", Direction.DEBIT, CARD_TEXT + "Zyxq Wvut on 01-01-2026")).isEmpty());
        assertMatch("PERSON", Step.PEOPLE, match(withText("Zyxq Wvut", Direction.DEBIT, "Rs 100 debited via UPI to Zyxq Wvut")));
    }

    @Test
    void singleWordNamesArePeopleOnlyWhenTheMailSaysPerson() {
        assertMatch("PERSON", Step.PEOPLE, match(withText("Zyxqwv", Direction.DEBIT, "debited UPI REF NO 1 P2P-Zyxqwv-UPI- HEAD OFFICE")));
        assertTrue(categorize(withText("Zyxqwv", Direction.DEBIT, "debited via UPI to Zyxqwv")).isEmpty());
        assertMatch("PERSON", Step.PEOPLE, match(withText("Mr Zyxqwv", Direction.CREDIT, "credited by VPA zy@okaxis Mr Zyxqwv")));
    }

    @Test
    void merchantQrAndBusinessHandlesAreNotPeople() {
        assertTrue(categorize(debit("q123456789@ybl")).isEmpty());
        assertTrue(categorize(debit("9876543210@okbizaxis")).isEmpty());
    }

    @Test
    void transfersToTheHolderOwnNameAreLinks() {
        String text = "Dear Zyxq Wvut A, Rs 500 has been debited to VPA zyxqwvut1-1@okhdfcbank on 01-01-26 via UPI";
        assertMatch("self_transfer", Step.LINK, match(withText("ZYXQ WVUT A", Direction.DEBIT, text)));
        assertMatch("self_transfer", Step.LINK, match(withText("A ZYXQ WVUT", Direction.CREDIT, text)));
        assertMatch("self_transfer", Step.LINK, match(withText("zyxqwvut1-1@okhdfcbank", Direction.DEBIT, text)));
        assertMatch("self_transfer", Step.LINK, match(new CategorizeInput("Zyxq Wvut", Direction.DEBIT, null, null, null, "Dear Customer, via UPI", null, null, "Zyxq Wvut")));
        assertMatch("PERSON", Step.PEOPLE, match(withText("Bhavna Rao", Direction.DEBIT, text)));
    }

    @Test
    void amazonPayRewardCreditIsCashbackNotAWalletLoad() {
        assertMatch("cashback", Step.KEYWORD, match(withText(null, Direction.CREDIT,
                "Thank you. Your reward points have been added to your Amazon Pay balance")));
        assertMatch(null, Step.LINK, match(withText(null, Direction.CREDIT, "Money added to your Amazon Pay balance")));
    }

    @Test
    void amazonCardSpendsAreShoppingAndRefundWordsOnDebitsAreNot() {
        assertMatch("shopping", Step.MERCHANT, match(debit("Amazon")));
        assertMatch("groceries", Step.KEYWORD, match(debit("IND*Amazon.in - Grocer")));
        assertMatch("travel.flights", Step.MERCHANT, match(debit("AMAZON PAY IN FLIGHTS")));
        assertMatch("subscriptions.software", Step.KEYWORD, match(withText("Google Play recharge", Direction.DEBIT, "refundable once redeemed")));
    }

    @Test
    void cardAutopayToAMerchantIsNotACardBill() {
        BuiltinMatch m = match(withText("Amazon", Direction.DEBIT, "payment towards Amazon, with Standing Instruction ABC, through your Credit Card 0005"));
        assertEquals(Step.MERCHANT, m.step());
    }

    @Test
    void smallHotelBillsAreDiningAndBigOnesAreHotels() {
        assertMatch("food.dining", Step.KEYWORD, match(new CategorizeInput("Hotel Zyxq", Direction.DEBIT, new BigDecimal("320"), null, null, "x", null, null)));
        assertMatch("travel.hotels", Step.KEYWORD, match(new CategorizeInput("Hotel Zyxq", Direction.DEBIT, new BigDecimal("5000"), null, null, "x", null, null)));
        assertMatch("travel.hotels", Step.KEYWORD, match(debit("Zyxq Residency")));
    }

    @Test
    void travelsAreBusesAndAcademiesAreCourses() {
        assertMatch("travel.buses", Step.KEYWORD, match(debit("Zyxq Travels")));
        assertMatch("travel", Step.MERCHANT, match(debit("Akbar Travels")));
        assertMatch("education.courses", Step.KEYWORD, match(debit("Zyxq Academy Private Limited")));
        assertMatch("education", Step.KEYWORD, match(debit("Zyxq Public School")));
    }

    @Test
    void cutOffBrandFamiliesAreAmbiguousAndLeftToTheAi() {
        assertTrue(categorize(withText("ADITYA BIRL", Direction.DEBIT, CARD_TEXT + "ADITYA BIRL")).isEmpty());
        assertMatch("food.delivery", Step.MERCHANT, match(debit("Swiggy IN")));
    }

    @Test
    void creditsFromMerchantsAreRefundsOrNothing() {
        assertMatch("refund", Step.MERCHANT, match(withText("Netflix", Direction.CREDIT, "credited")));
        // a lender or donation merchant has no refund reading
        assertTrue(categorize(withText("Ketto", Direction.CREDIT, "credited")).isEmpty());
    }

    @Test
    void ownImpsMoveAndWalletCardLoadAreLinks() {
        assertMatch("self_transfer", Step.LINK, match(withText(null, Direction.DEBIT,
                "INR 400 has been debited from your account ending 0001 on 01-08-26 and credited to the account ending 0002 via IMPS")));
        assertMatch("top_up", Step.LINK, match(debit("MOBIKWIK")));
    }

    @Test
    void gamesAndCashbackVpaAndDirectTaxes() {
        assertMatch("entertainment.gaming", Step.KEYWORD, match(debit("Zyxq Games Hub")));
        assertMatch("cashback", Step.KEYWORD, match(withText("Zyxq Tech", Direction.CREDIT, "credited by VPA zyxqcashback@axisbank")));
        assertMatch("taxes", Step.KEYWORD, match(debit("Central Board of Direct Taxes")));
    }

    // V3 rules

    @Test
    void amazonPayPaidToSingleWordIsNotPeople() {
        assertTrue(categorize(new CategorizeInput("Zyxqwv", Direction.DEBIT, new BigDecimal("50"), "no-reply@pay.test",
                "Rs.50.00 was paid to Zyxqwv", "Your payment was successful. UPI Reference ID 1", null, null)).isEmpty());
    }

    @Test
    void creditCardUpiPayeeIsNeverPeople() {
        String text = "Rs.100 has been debited from your RuPay Credit Card (ending 1111) Paid to Zyxq Wvut via UPI";
        assertTrue(categorize(withText("Zyxq Wvut", Direction.DEBIT, text)).isEmpty());
        assertMatch("card_bill", Step.LINK, match(withText("EXAMPLE BANK CREDIT CARD", Direction.DEBIT,
                "Rs.100 debited from account 0001 to VPA billdeskpg.examplecard@bank EXAMPLE BANK CREDIT CARD via UPI")));
    }

    @Test
    void prepaidPostpaidAndUpiReversalsAndAmazonWalletLoad() {
        assertMatch("bills.mobile", Step.KEYWORD, match(withText("Zyxq Prepaid", Direction.DEBIT, "Convenience fee Rs 0")));
        assertMatch("refund", Step.KEYWORD, match(withText("ZYXQ WVUT", Direction.CREDIT, "credited on account of REVERSAL UPI 123 REV P2A-ZYXQ WVUT")));
        assertMatch(null, Step.LINK, match(withText(null, Direction.CREDIT, "Hi A, You have added Rs 1000.00 successfully to your Amazon Pay Wallet")));
        assertMatch("top_up", Step.LINK, match(debit("IND*Amazon Gift Card")));
        assertMatch("self_transfer", Step.LINK, match(withText(null, Direction.DEBIT, "Rs.1000 has been debited from account **0001 to account **0002 on 02-10-24")));
    }

    @Test
    void airtelPaymentsAndRelianceRetailAreLeftToTheAi() {
        assertTrue(categorize(withText("AIRTEL PAYM", Direction.DEBIT, CARD_TEXT + "AIRTEL PAYM")).isEmpty());
        assertTrue(categorize(withText("Reliance Retail", Direction.DEBIT, CARD_TEXT + "Reliance Retail")).isEmpty());
        assertMatch("shopping", Step.MERCHANT, match(debit("Lenskart")));
    }

    @Test
    void resortsAreHotelsAndTrailingHotelIsDiningWhenSmall() {
        assertMatch("travel.hotels", Step.KEYWORD, match(debit("Zyxq Resort and Spa")));
        assertMatch("food.dining", Step.KEYWORD, match(debit("Zyxq Hotel")));
    }

    @Test
    void diningWordsOnTheCounterparty() {
        assertMatch("food.dining", Step.KEYWORD, match(debit("Zyxq Foods Pvt Ltd")));
        assertMatch("food.dining", Step.KEYWORD, match(debit("Zyxq Veg")));
        assertMatch("food.dining", Step.KEYWORD, match(debit("Zyxq Vilas")));
        assertMatch("groceries", Step.KEYWORD, match(debit("Zyxq Mandi")));
        assertMatch("PERSON", Step.PEOPLE, match(debit("Mandy Zyxqwv")));
    }

    // review fixes

    private static void assertNotMerchant(CategorizeInput in) {
        Optional<BuiltinMatch> m = categorize(in);
        assertTrue(m.isEmpty() || m.get().step() != Step.MERCHANT, "unexpected merchant match: " + m);
    }

    @Test
    void shortBrandNamesDoNotMatchInsideLongerWords() {
        assertNotMerchant(debit("Digital Payments"));
        assertNotMerchant(debit("Shellys Corner"));
        assertNotMerchant(debit("Steamers"));
        assertNotMerchant(debit("Kayaking Club"));
        assertNotMerchant(debit("Trident Group"));
        assertMatch("insurance", Step.KEYWORD, match(debit("Digit Insurance")));
        assertMatch("transport.fuel", Step.MERCHANT, match(debit("Shell")));
    }

    @Test
    void goldWordsAreNotAutomaticallyJewellery() {
        assertMatch("shopping.jewellery", Step.KEYWORD, match(debit("Zyxq Gold Palace")));
        assertMatch("investments", Step.KEYWORD, match(debit("Sovereign Gold Bond")));
        assertMatch("investments", Step.KEYWORD, match(debit("Digital Gold")));
        assertTrue(categorize(debit("Zomato Gold")).isEmpty() || !"shopping.jewellery".equals(keyOf(categorize(debit("Zomato Gold")).get())));
    }

    @Test
    void relativesAreNotSelfTransfers() {
        String text = "Dear Zyxq Wvut, Rs 500 has been debited via UPI";
        assertMatch("PERSON", Step.PEOPLE, match(withText("K Wvut", Direction.DEBIT, text)));
        assertMatch("PERSON", Step.PEOPLE, match(withText("Zyxq Wvut", Direction.DEBIT, "Hi Zyxq, Rs 500 debited via UPI")));
        assertMatch("self_transfer", Step.LINK, match(withText("Zyxq Wvut", Direction.DEBIT, text)));
    }

    @Test
    void hugeInputsDoNotOverflowTheStack() {
        String big = "Zyxq ".repeat(2000);
        assertTrue(categorize(withText(big, Direction.DEBIT, big + " via UPI")).isEmpty());
        assertTrue(categorize(withText("Zyxq Wvut", Direction.DEBIT, big)).isPresent());
    }

    @Test
    void footerWordsDoNotMakeACashbackOrRefund() {
        String footer = "Rs 500 credited by UPI. Offers: earn cashback on every spend. See our refund policy.";
        BuiltinMatch m = match(withText("Zyxq Wvut", Direction.CREDIT, footer));
        assertMatch("PERSON", Step.PEOPLE, m);
        assertMatch("cashback", Step.KEYWORD, match(withText(null, Direction.CREDIT, "Cashback of Rs 50 has been credited to your account")));
        assertMatch("refund", Step.KEYWORD, match(withText(null, Direction.CREDIT, "Your refund of Rs 80 has been processed")));
    }

    // Wave 1b: keys, direction, transit split, counterparty name

    @Test
    void transitIsSplitIntoMetroLocalBusAndGeneral() {
        assertMatch("transport.metro", Step.MERCHANT, match(debit("Delhi Metro")));
        assertMatch("transport.metro", Step.MERCHANT, match(debit("BMRCL")));
        assertMatch("transport.local_bus", Step.MERCHANT, match(debit("BMTC")));
        assertMatch("transport", Step.MERCHANT, match(debit("Tummoc")));
        assertMatch("transport", Step.MCC, match(in("Zyxq", Direction.DEBIT, "Alert", "", "4111")));
    }

    @Test
    void creditsOfASpendMerchantAreARefundAndSomeHaveACreditReading() {
        assertMatch("refund", Step.MERCHANT, match(in("Swiggy", Direction.CREDIT, "Alert", "credited", null)));
        assertMatch("refund", Step.MCC, match(in("Zyxq", Direction.CREDIT, "Alert", "", "5411")));
        assertMatch("earnings.rent", Step.KEYWORD, match(withText("Zyxq Wvut", Direction.CREDIT, "monthly rent received")));
        assertMatch("bills.rent", Step.KEYWORD, match(withText("Zyxq Wvut", Direction.DEBIT, "monthly rent paid")));
        assertMatch("redemption", Step.MERCHANT, match(in("Zerodha", Direction.CREDIT, "Alert", "credited", null)));
    }

    @Test
    void incomeOnlyKeysHaveNoAnswerOnDebits() {
        assertMatch("salary.salary", Step.KEYWORD, match(in("Acme Corp", Direction.CREDIT, "Credit alert", "Salary credit for October", null)));
        Optional<BuiltinMatch> debitSalary = categorize(in("Acme", Direction.DEBIT, "Debit alert", "Salary credit for October", null));
        assertTrue(debitSalary.isEmpty() || !"salary.salary".equals(keyOf(debitSalary.get())));
    }

    @Test
    void everyAnswerIsAValidKeyForItsDirection() {
        var catalog = com.financeos.domain.category.CategoryCatalog.shared();
        for (Direction d : Direction.values()) {
            var type = d == Direction.CREDIT ? com.financeos.domain.transaction.TransactionType.CREDIT
                    : com.financeos.domain.transaction.TransactionType.DEBIT;
            for (String cp : new String[]{"Swiggy", "Uber", "Netflix", "Zerodha", "LIC", "Delhi Metro", "BMTC", "Tummoc", "Ketto", "TTD", "Amul"}) {
                BuiltinMatch m = categorizer.categorize(in(cp, d, "Alert", "x", null));
                if (m.hasCategory()) {
                    assertTrue(catalog.fits(type, m.categoryKey(), m.subcategoryKey()), cp + " " + d + " -> " + keyOf(m));
                }
            }
        }
    }

    @Test
    void personGetsNoCategoryButANormalisedName() {
        BuiltinMatch m = match(debit("UPI_MR RAVI KUMAR SHARMA"));
        assertMatch("PERSON", Step.PEOPLE, m);
        assertEquals("mr ravi kumar sharma", m.counterpartyName());
        assertNull(m.subcategoryKey());
    }

    @Test
    void counterpartyNameIsPresentForMatchedAndUnmatchedInputs() {
        assertEquals("swiggy", categorizer.categorize(debit("SWIGGY LIMITED")).counterpartyName());
        BuiltinMatch none = categorizer.categorize(debit("Zyxq Traders Chennai"));
        assertNull(none.step());
        assertEquals("zyxq traders chennai", none.counterpartyName());
        assertEquals("zyxq", categorizer.categorize(in("Zyxq", Direction.DEBIT, "", "", null)).counterpartyName());
        assertNull(categorizer.categorize(new CategorizeInput(null, null, null, null, null, null, null, null)).counterpartyName());
    }
}
