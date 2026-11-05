package com.financeos.domain.nps.link;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NpsPaymentPatternsTest {

    private static final LocalDate POSTED = LocalDate.of(2026, 11, 10);

    @Test
    void realPaymentLinesMatch() {
        assertTrue(NpsPaymentPatterns.looksLikeNps("RAZORPAY*NPS TRUST", null));
        assertTrue(NpsPaymentPatterns.looksLikeNps("ENPS/123456/NPS TRUST", null));
        assertTrue(NpsPaymentPatterns.looksLikeNps("UPI-xyz", "Payment to Protean eGov"));
        assertTrue(NpsPaymentPatterns.looksLikeNps("NSDL CRA ENPS", null));
        assertTrue(NpsPaymentPatterns.looksLikeNps("e-nps contribution", null));
        assertTrue(NpsPaymentPatterns.looksLikeNps(null, "PFRDA CONTRIB"));
        assertTrue(NpsPaymentPatterns.looksLikeNps("national pension system", null));
    }

    @Test
    void kycVerificationAndUnrelatedLinesDoNotMatch() {
        assertFalse(NpsPaymentPatterns.looksLikeNps("ENPS KYC VERIFICATION", null));
        assertFalse(NpsPaymentPatterns.looksLikeNps("NPS TRUST", "penny drop"));
        assertFalse(NpsPaymentPatterns.looksLikeNps("SNAPS CAFE", null));
        assertFalse(NpsPaymentPatterns.looksLikeNps("Swiggy", "Swiggy order"));
        assertFalse(NpsPaymentPatterns.looksLikeNps(null, null));
        assertFalse(NpsPaymentPatterns.looksLikeNps(" ", ""));
    }

    @Test
    void thePaymentMayExceedTheContributionByTheFeeOnly() {
        BigDecimal amount = new BigDecimal("1000.00");
        assertTrue(NpsPaymentPatterns.fits(new BigDecimal("1000.00"), POSTED, amount, POSTED));
        assertTrue(NpsPaymentPatterns.fits(new BigDecimal("1030.00"), POSTED, amount, POSTED)); // Rs 30 floor
        assertFalse(NpsPaymentPatterns.fits(new BigDecimal("1030.01"), POSTED, amount, POSTED));
        assertFalse(NpsPaymentPatterns.fits(new BigDecimal("999.99"), POSTED, amount, POSTED)); // never below
        // 0.3% of 50,000 is 150, above the floor
        assertTrue(NpsPaymentPatterns.fits(new BigDecimal("50150.00"), POSTED, new BigDecimal("50000"), POSTED));
        assertFalse(NpsPaymentPatterns.fits(new BigDecimal("50150.01"), POSTED, new BigDecimal("50000"), POSTED));
    }

    @Test
    void thePaymentIsDatedUpToSevenDaysBeforeThePostedDateNeverAfter() {
        BigDecimal amount = new BigDecimal("1000");
        assertTrue(NpsPaymentPatterns.fits(amount, POSTED.minusDays(7), amount, POSTED));
        assertFalse(NpsPaymentPatterns.fits(amount, POSTED.minusDays(8), amount, POSTED));
        assertFalse(NpsPaymentPatterns.fits(amount, POSTED.plusDays(1), amount, POSTED));
    }

    @Test
    void missingOrNonPositiveAmountsNeverFit() {
        assertFalse(NpsPaymentPatterns.fits(BigDecimal.TEN, POSTED, null, POSTED));
        assertFalse(NpsPaymentPatterns.fits(BigDecimal.TEN, POSTED, BigDecimal.ZERO, POSTED));
    }
}
