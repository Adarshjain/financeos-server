package com.financeos.llm.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class LlmKeyEncryptionServiceTest {

    private static final String VALID_MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]); // 32 zero bytes

    @Test
    public void testEncryptionAndDecryptionRoundTrip() {
        LlmKeyEncryptionService service = new LlmKeyEncryptionService(VALID_MASTER_KEY);
        assertTrue(service.isConfigured());

        String rawKey = "AIzaSyD-TestKey123456789";
        String encrypted = service.encrypt(rawKey);

        assertNotNull(encrypted);
        assertNotEquals(rawKey, encrypted);

        String decrypted = service.decrypt(encrypted);
        assertEquals(rawKey, decrypted);
    }

    @Test
    public void testExtractLast4() {
        assertEquals("6789", LlmKeyEncryptionService.extractLast4("AIzaSyD-TestKey123456789"));
        assertEquals("abcd", LlmKeyEncryptionService.extractLast4("abcd"));
        assertEquals("xy", LlmKeyEncryptionService.extractLast4("xy"));
        assertEquals("xxxx", LlmKeyEncryptionService.extractLast4(null));
    }

    @Test
    public void testMissingMasterKeyThrowsOnUsage() {
        LlmKeyEncryptionService service = new LlmKeyEncryptionService(null);
        assertFalse(service.isConfigured());
        assertThrows(IllegalStateException.class, () -> service.encrypt("test"));
        assertThrows(IllegalStateException.class, () -> service.decrypt("test"));
    }

    @Test
    public void testInvalidMasterKeyLengthThrowsOnStartup() {
        String invalidShortKey = Base64.getEncoder().encodeToString(new byte[16]); // 16 bytes instead of 32
        assertThrows(IllegalStateException.class, () -> new LlmKeyEncryptionService(invalidShortKey));
    }
}
