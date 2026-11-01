package com.financeos.domain.categorization.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All inputs are made up. */
class StatementLineTest {

    @Test
    void posLineLosesRailCardNumberAndCity() {
        assertEquals("SWIGGY", StatementLine.clean("POS 4111XXXXXXXX1234 SWIGGY BLR").name());
        assertEquals("SWIGGY", StatementLine.clean("POS 411111******1234 SWIGGY BANGALORE IN").name());
    }

    @Test
    void upiLineKeepsPayeeNameAndHandle() {
        StatementLine.Cleaned c = StatementLine.clean("UPI/412345678901/SWIGGY/swiggy@icici/Payment from Phone");
        assertEquals("SWIGGY", c.name());
        assertEquals("swiggy@icici", c.handle());
        assertEquals("SWIGGY swiggy@icici", c.asCounterparty());
    }

    @Test
    void hyphenatedUpiLineKeepsNameBeforeTheHandle() {
        StatementLine.Cleaned c = StatementLine.clean("UPI-CULT FIT-cultfit@axisbank-YESB0000262-807290222791-UPI");
        assertEquals("CULT FIT", c.name());
        assertEquals("cultfit@axisbank", c.handle());
    }

    @Test
    void upiLineWithOnlyFillerKeepsTheHandleAlone() {
        StatementLine.Cleaned c = StatementLine.clean("UPI/123456789012/Payment from Phone/ravi.k@ybl/YESB");
        assertNull(c.name());
        assertEquals("ravi.k@ybl", c.handle());
    }

    @Test
    void impsNeftAchBillAndEcomRails() {
        assertEquals("RAVI KUMAR", StatementLine.clean("IMPS/P2A/405730044314/RAVI KUMAR/SBIN").name());
        assertEquals("ACME CONSULTING", StatementLine.clean("NEFT-HDFCN52026102812345-ACME CONSULTING").name());
        assertEquals("ZERODHA BROKING", StatementLine.clean("ACH D- ZERODHA BROKING-123456").name());
        assertEquals("AIRTEL", StatementLine.clean("BIL/BPAY/000123456/AIRTEL").name());
        assertEquals("OLA", StatementLine.clean("ECOM PUR/OLA/HYDERABAD/14-Oct-26").name());
        assertEquals("RENT", StatementLine.clean("INF/INFT/0123456789/RENT").name());
    }

    @Test
    void datesAndTimesAreRemovedButNamesLikeMayurAreNot() {
        assertEquals("MAYUR STORES", StatementLine.clean("POS 1234 MAYUR STORES 7 MAY 2026 BLR").name());
        assertEquals("CAFE", StatementLine.clean("POS 1234 CAFE 14/10/2026 21:05:33").name());
    }

    @Test
    void shortNumbersInsideANameSurvive() {
        assertEquals("OFFICE 365", StatementLine.clean("POS 1234 OFFICE 365").name());
        assertEquals("TATA 1MG", StatementLine.clean("UPI/123456789012/TATA 1MG/tata1mg@ybl/x").name());
    }

    @Test
    void aLineThatIsOnlyBankAndPlaceHasNoPayee() {
        assertNull(StatementLine.clean("ATW-512345XXXXXX1234-HDFC BANK ATM-BANGALORE").name());
        assertNull(StatementLine.clean("POS 4111XXXXXXXX1234 BLR").name());
    }

    @Test
    void placeIsOnlyDroppedFromTheEnd() {
        assertEquals("DELHI BELLY CAFE", StatementLine.clean("POS 1234 DELHI BELLY CAFE NEW DELHI").name());
        assertEquals("BLUE DART", StatementLine.clean("POS 1234 BLUE DART MUMBAI IN").name());
    }

    @Test
    void plainNamesAreNotStatementLines() {
        assertFalse(StatementLine.looksLike("Swiggy Instamart"));
        assertFalse(StatementLine.looksLike("Ravi Kumar"));
        assertTrue(StatementLine.looksLike("POS SWIGGY"));
        assertTrue(StatementLine.looksLike("SWIGGY 123456"));
        assertTrue(StatementLine.looksLike("a/b"));
    }

    @Test
    void emptyAndOverlongInputs() {
        assertNull(StatementLine.clean(null).name());
        assertNull(StatementLine.clean("  ").name());
        assertFalse(StatementLine.looksLike("x/".repeat(150)));
    }
}
