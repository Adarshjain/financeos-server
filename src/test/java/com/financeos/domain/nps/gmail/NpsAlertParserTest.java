package com.financeos.domain.nps.gmail;

import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsTier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpsAlertParserTest {

    private static final String REQUEST = "The contribution in your NPS account through eNPS portal is currently under "
            + "process and the related details are as follow:\n1. PRAN : XXXXXXXX1234\n2. Acknowledgment Number : 12345678\n"
            + "3. Tier Type : Tier I account\n4. Amount : Rs. 37350.00\n";
    private static final String CREDIT = "Units with NAV of 15/01/2025 have been credited for TIER1 in your PRAN XX1234 "
            + "against Rs. 37350.00 as Voluntary contribution on 17/01/2025.";

    @Test
    void requestConfirmationInPlainText() {
        NpsAlertParser.Request r = NpsAlertParser.request(NpsAlertParser.text(REQUEST, null)).orElseThrow();
        assertEquals("1234", r.pranLast4());
        assertEquals("12345678", r.ack());
        assertEquals(NpsTier.I, r.tier());
        assertEquals(new BigDecimal("37350.00"), r.amount());
    }

    @Test
    void requestConfirmationInHtmlWithTagsAndEntities() {
        String html = "<html><body><p>The contribution is under process:</p><ol><li>PRAN&nbsp;: <b>XXXXXXXX9876</b></li>"
                + "<li>Acknowledgment Number : <b>555</b></li><li>Tier Type : Tier II account</li>"
                + "<li>Amount : Rs. 1,25,000.50</li></ol></body></html>";
        NpsAlertParser.Request r = NpsAlertParser.request(NpsAlertParser.text("", html)).orElseThrow();
        assertEquals("9876", r.pranLast4());
        assertEquals("555", r.ack());
        assertEquals(NpsTier.II, r.tier());
        assertEquals(new BigDecimal("125000.50"), r.amount());
    }

    @Test
    void creditIntimationVoluntaryIsSelfAndTier1IsI() {
        NpsAlertParser.Credit c = NpsAlertParser.credit(NpsAlertParser.text(CREDIT, null)).orElseThrow();
        assertEquals("1234", c.pranLast4());
        assertEquals(NpsTier.I, c.tier());
        assertEquals(new BigDecimal("37350.00"), c.amount());
        assertEquals(NpsContributor.SELF, c.contributor());
        assertEquals(LocalDate.of(2025, 1, 15), c.navDate());
        assertEquals(LocalDate.of(2025, 1, 17), c.creditDate());
    }

    @Test
    void creditIntimationEmployerAndTier2() {
        String text = "Units with NAV of 03/02/2025 have been credited for TIER2 in your PRAN XX4321 against Rs. 5000.00 as "
                + "Employer contribution on 05/02/2025.";
        NpsAlertParser.Credit c = NpsAlertParser.credit(text).orElseThrow();
        assertEquals(NpsContributor.EMPLOYER, c.contributor());
        assertEquals(NpsTier.II, c.tier());
        for (String word : new String[]{"Employee", "Subscriber"}) {
            assertEquals(NpsContributor.SELF, NpsAlertParser.credit(text.replace("Employer", word)).orElseThrow().contributor());
        }
    }

    @Test
    void creditIntimationInHtmlSplitAcrossTags() {
        String html = "<p>Units with NAV of <b>15/01/2025</b> have been credited for <b>TIER1</b> in your PRAN <b>XX1234</b> "
                + "against <b>Rs. 37350.00</b> as Voluntary contribution on <b>17/01/2025</b>.</p>";
        assertTrue(NpsAlertParser.credit(NpsAlertParser.text("", html)).isPresent());
    }

    @Test
    void otherMailsAreNotAlerts() {
        assertTrue(NpsAlertParser.request("Your eNPS password has been changed").isEmpty());
        assertTrue(NpsAlertParser.credit("Your NPS statement is attached").isEmpty());
        assertTrue(NpsAlertParser.credit(REQUEST).isEmpty());
        assertTrue(NpsAlertParser.request(CREDIT).isEmpty());
    }
}
