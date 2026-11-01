package com.financeos.domain.categorization.builtin;

import com.financeos.gmail.extract.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The description fallback: a bank-statement line is both counterparty and text. All inputs are made up. */
class BuiltinCategorizerStatementLineTest {

    private static BuiltinCategorizer categorizer;

    @BeforeAll
    static void load() {
        categorizer = new BuiltinCategorizer();
    }

    private static BuiltinMatch line(String description, Direction direction) {
        return categorizer.categorize(new CategorizeInput(description, direction, null, null, null, description, null, null));
    }

    private static BuiltinMatch debit(String description) {
        return line(description, Direction.DEBIT);
    }

    private static String key(BuiltinMatch m) {
        return m.subcategoryKey() != null ? m.subcategoryKey() : m.categoryKey();
    }

    @Test
    void merchantInsideAPosLine() {
        BuiltinMatch m = debit("POS 4111XXXXXXXX1234 SWIGGY BLR");
        assertEquals("food.delivery", key(m));
        assertEquals(Step.MERCHANT, m.step());
        assertEquals("swiggy", m.counterpartyName());
    }

    @Test
    void merchantInsideUpiNeftAchBillAndEcomLines() {
        assertEquals("food.delivery", key(debit("UPI/412345678901/SWIGGY/swiggy@icici/Payment from Phone")));
        assertEquals("food.delivery", key(debit("UPI-SWIGGY-swiggy@icici-YESB0000262-412345678901-UPI")));
        assertEquals("subscriptions.music", key(debit("ACH D- SPOTIFY-123456")));
        assertEquals("bills.mobile", key(debit("BIL/BPAY/000123456/AIRTEL")));
        assertEquals("transport.cabs", key(debit("ECOM PUR/OLA/HYDERABAD/14-Oct-26")));
    }

    @Test
    void longestMerchantNameWinsInsideALongerLine() {
        // "tp swiggy instamart": the two-word brand beats the one-word one
        assertEquals("groceries", debit("POS 4111XXXXXXXX1234 SWIGGY INSTAMART BLR").categoryKey());
    }

    @Test
    void merchantIsFoundOnWordBoundariesOnly() {
        // "swiggy" sits inside a longer word: no merchant
        BuiltinMatch m = debit("POS 4111XXXXXXXX1234 SWIGGYSTONE TRADERS BLR");
        assertNull(m.categoryKey());
        assertNull(m.step());
    }

    @Test
    void shortMerchantNameNeverInsideALongerWord() {
        // "ola" is a merchant; "olam" and "bolan" contain it
        assertNull(debit("POS 4111XXXXXXXX1234 OLAM TRADERS BLR").categoryKey());
        assertNull(debit("POS 4111XXXXXXXX1234 BOLAN TRADERS BLR").categoryKey());
    }

    @Test
    void shortMerchantNameNotTakenFromInsideAPersonsName() {
        BuiltinMatch m = debit("UPI/412345678901/OLA SHARMA/ola.sharma@ybl/Payment from Phone");
        assertNull(m.categoryKey());
        assertEquals(Step.PEOPLE, m.step());
        assertTrue(m.person());
        assertEquals("ola sharma", m.counterpartyName());
        BuiltinMatch imps = debit("IMPS/P2A/405730044314/OLA SHARMA/SBIN");
        assertNull(imps.categoryKey());
        assertTrue(imps.person());
    }

    @Test
    void shortMerchantNameStillMatchesAsAWholeToken() {
        assertEquals("transport.cabs", key(debit("POS 4111XXXXXXXX1234 OLA BLR")));
        assertEquals("transport.cabs", key(debit("UPI/412345678901/OLA/ola@icici/Payment from Phone")));
    }

    @Test
    void anUndecidedBrandInsideALineStaysUndecided() {
        BuiltinMatch m = debit("POS 4111XXXXXXXX1234 RELIANCE RETAIL BLR");
        assertNull(m.categoryKey());
    }

    @Test
    void upiPersonLineIsAPersonWithTheNameAsCounterparty() {
        BuiltinMatch m = debit("UPI/DR/330872542299/RAVI KUMAR/YESB/ravi.kumar@ybl/Payment from Phone");
        assertNull(m.categoryKey());
        assertTrue(m.person());
        assertEquals("ravi kumar", m.counterpartyName());
        BuiltinMatch hyphen = debit("UPI-RAVI KUMAR-ravi.kumar@okicici-HDFC0000001-351862297631-UPI");
        assertTrue(hyphen.person());
        assertEquals("ravi kumar", hyphen.counterpartyName());
    }

    @Test
    void sameMerchantOnDifferentLinesGroupsUnderOneName() {
        assertEquals(debit("POS 1111XXXXXXXX1234 SWIGGY BLR").counterpartyName(),
                debit("UPI/412345678901/SWIGGY/swiggy@icici/Payment from Phone").counterpartyName());
    }

    @Test
    void aLineOfOnlyBankAndPlaceNamesNobody() {
        BuiltinMatch m = debit("ATW-512345XXXXXX1234-HDFC BANK ATM-BANGALORE");
        assertNull(m.categoryKey());
        assertFalse(m.person());
    }

    @Test
    void creditLineReadsPerDirection() {
        BuiltinMatch m = line("UPI/412345678901/SWIGGY/swiggy@icici/Payment from Phone", Direction.CREDIT);
        assertEquals("refund", m.categoryKey());
    }
}
