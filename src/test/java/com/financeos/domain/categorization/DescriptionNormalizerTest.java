package com.financeos.domain.categorization;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DescriptionNormalizerTest {

    @Test
    public void testNormalizeSwiggy() {
        String original = "UPI-SWIGGY LIMITED-swiggy.stores@icici-REF510912345678";
        String expected = "SWIGGY SWIGGY STORES ICICI";
        assertEquals(expected, DescriptionNormalizer.normalize(original));
    }

    @Test
    public void testNormalizeAmazon() {
        String original = "POS 4123XX9128 AMAZON PAY";
        String expected = "AMAZON PAY";
        assertEquals(expected, DescriptionNormalizer.normalize(original));
    }

    @Test
    public void testNormalizeNoiseAndDigits() {
        String original = "NEFT PVT LTD TXN123456 FOR STARBUCKS IN";
        String expected = "FOR STARBUCKS";
        assertEquals(expected, DescriptionNormalizer.normalize(original));
    }

    @Test
    public void testNormalizeNullAndBlank() {
        assertEquals("", DescriptionNormalizer.normalize(null));
        assertEquals("", DescriptionNormalizer.normalize("   "));
    }
}
