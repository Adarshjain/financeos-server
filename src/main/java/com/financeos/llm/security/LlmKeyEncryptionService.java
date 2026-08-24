package com.financeos.llm.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class LlmKeyEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(LlmKeyEncryptionService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public LlmKeyEncryptionService(@Value("${llm.keys.master-key:#{null}}") String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            this.secretKey = null;
            log.error("LLM_KEYS_MASTER_KEY is not configured! Key storage and encryption will be unavailable until LLM_KEYS_MASTER_KEY is set in environment.");
        } else {
            try {
                byte[] decoded = Base64.getDecoder().decode(masterKeyBase64.trim());
                if (decoded.length != 32) {
                    throw new IllegalArgumentException("LLM_KEYS_MASTER_KEY must be a 32-byte (256-bit) base64-encoded string, decoded length was: " + decoded.length);
                }
                this.secretKey = new SecretKeySpec(decoded, "AES");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize LlmKeyEncryptionService: invalid LLM_KEYS_MASTER_KEY base64 format", e);
            }
        }
    }

    public boolean isConfigured() {
        return secretKey != null;
    }

    private void ensureConfigured() {
        if (secretKey == null) {
            throw new IllegalStateException("LLM_KEYS_MASTER_KEY is not configured in environment/properties");
        }
    }

    public String encrypt(String plainText) {
        ensureConfigured();
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt LLM API key", e);
        }
    }

    public String decrypt(String cipherTextBase64) {
        ensureConfigured();
        if (cipherTextBase64 == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherTextBase64);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Invalid ciphertext length");
            }

            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

            byte[] encrypted = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt LLM API key", e);
        }
    }

    public static String extractLast4(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "xxxx";
        }
        String trimmed = rawKey.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - 4);
    }
}
