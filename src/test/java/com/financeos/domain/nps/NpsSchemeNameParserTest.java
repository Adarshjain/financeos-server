package com.financeos.domain.nps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NpsSchemeNameParserTest {

    @Test
    void readsClassTierAndDirectVariant() {
        NpsSchemeNameParser.Meta m = NpsSchemeNameParser.parse(
                "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT");
        assertEquals("E", m.assetClass());
        assertEquals(NpsSchemeTier.I, m.tier());
        assertEquals(NpsVariant.DIRECT, m.variant());
    }

    @Test
    void tierTwoIsNotMistakenForTierOne() {
        NpsSchemeNameParser.Meta m = NpsSchemeNameParser.parse("DSP PENSION FUND MANAGERS PRIVATE LIMITED SCHEME G - TIER II POP");
        assertEquals("G", m.assetClass());
        assertEquals(NpsSchemeTier.II, m.tier());
        assertEquals(NpsVariant.POP, m.variant());
    }

    @Test
    void gsVariantAndClassesCAndA() {
        assertEquals(NpsVariant.GS, NpsSchemeNameParser.parse("AXIS PENSION FUND MANAGEMENT LIMITED SCHEME G - TIER I GS").variant());
        assertEquals("C", NpsSchemeNameParser.parse("KOTAK PENSION FUND SCHEME C - TIER II DIRECT").assetClass());
        assertEquals("A", NpsSchemeNameParser.parse("TATA PENSION MANAGEMENT LIMITED SCHEME A - TIER I").assetClass());
    }

    @Test
    void taxSaverIsItsOwnTierAndHasNoClass() {
        NpsSchemeNameParser.Meta m = NpsSchemeNameParser.parse("ADITYA BIRLA SUN LIFE PENSION FUND SCHEME TAX SAVER TIER II");
        assertNull(m.assetClass());
        assertEquals(NpsSchemeTier.TTS, m.tier());
        assertEquals(NpsVariant.NONE, m.variant());
    }

    @Test
    void schemesWithNoClassOrTierGetNulls() {
        NpsSchemeNameParser.Meta m = NpsSchemeNameParser.parse("LIC PENSION FUND SCHEME - CENTRAL GOVT");
        assertNull(m.assetClass());
        assertNull(m.tier());
        assertEquals(NpsVariant.NONE, m.variant());
        NpsSchemeNameParser.Meta vatsalya = NpsSchemeNameParser.parse(
                "NPS TRUST - A/C ICICI PRUDENTIAL PENSION FUND - NPS VATSALYA SCHEME DIRECT");
        assertNull(vatsalya.assetClass());
        assertNull(vatsalya.tier());
        assertEquals(NpsVariant.DIRECT, vatsalya.variant());
        assertNull(NpsSchemeNameParser.parse(null).assetClass());
    }

    @Test
    void pfmCodeIsDigitsThreeToFiveOfTheSchemeCode() {
        assertEquals("PFM008", NpsSchemeNameParser.pfmCode("SM008001"));
        assertEquals("PFM001", NpsSchemeNameParser.pfmCode("SM001017"));
        assertNull(NpsSchemeNameParser.pfmCode("SM"));
    }
}
