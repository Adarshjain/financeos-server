package com.financeos.core.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceBytesTest {

    private EncryptionService service;

    @BeforeEach
    void setUp() {
        service = new EncryptionService();
        ReflectionTestUtils.setField(service, "base64Key", EncryptionService.generateKey());
        service.init();
    }

    private static byte[] sample() {
        byte[] b = new byte[4096];
        new Random(7).nextBytes(b);
        return b;
    }

    @Test
    void roundTripsBinaryData() {
        byte[] plain = sample();
        byte[] enc = service.encryptBytes(plain);
        assertEquals(12 + plain.length + 16, enc.length);
        assertFalse(Arrays.equals(plain, Arrays.copyOfRange(enc, 12, 12 + plain.length)));
        assertArrayEquals(plain, service.decryptBytes(enc));
    }

    @Test
    void usesFreshIvEachCall() {
        byte[] plain = sample();
        byte[] a = service.encryptBytes(plain);
        byte[] b = service.encryptBytes(plain);
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 0, 12), Arrays.copyOfRange(b, 0, 12)));
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    void tamperedCiphertextFails() {
        byte[] enc = service.encryptBytes(sample());
        enc[enc.length / 2] ^= 1;
        assertThrows(EncryptionException.class, () -> service.decryptBytes(enc));
    }

    @Test
    void tamperedTagFails() {
        byte[] enc = service.encryptBytes(sample());
        enc[enc.length - 1] ^= 1;
        assertThrows(EncryptionException.class, () -> service.decryptBytes(enc));
    }

    @Test
    void tooShortInputFails() {
        assertThrows(EncryptionException.class, () -> service.decryptBytes(new byte[0]));
        assertThrows(EncryptionException.class, () -> service.decryptBytes(new byte[27]));
        assertThrows(EncryptionException.class, () -> service.decryptBytes(null));
    }

    @Test
    void emptyArrayRoundTrips() {
        byte[] enc = service.encryptBytes(new byte[0]);
        assertEquals(28, enc.length);
        assertArrayEquals(new byte[0], service.decryptBytes(enc));
    }

    @Test
    void anotherKeyCannotDecrypt() {
        byte[] enc = service.encryptBytes(sample());
        EncryptionService other = new EncryptionService();
        ReflectionTestUtils.setField(other, "base64Key", EncryptionService.generateKey());
        other.init();
        assertThrows(EncryptionException.class, () -> other.decryptBytes(enc));
    }

    @Test
    void stringMethodsStillRoundTrip() {
        assertEquals("hello", service.decrypt(service.encrypt("hello")));
    }

    @Test
    void roundTripsWithMatchingAad() {
        byte[] plain = sample();
        byte[] aad = "u1:sha".getBytes();
        assertArrayEquals(plain, service.decryptBytes(service.encryptBytes(plain, aad), aad));
    }

    @Test
    void wrongOrMissingAadFails() {
        byte[] enc = service.encryptBytes(sample(), "u1:sha".getBytes());
        assertThrows(EncryptionException.class, () -> service.decryptBytes(enc, "u2:sha".getBytes()));
        assertThrows(EncryptionException.class, () -> service.decryptBytes(enc, "u1:sha2".getBytes()));
        assertThrows(EncryptionException.class, () -> service.decryptBytes(enc));
    }

    @Test
    void dataEncryptedWithoutAadFailsWhenAadIsGiven() {
        byte[] enc = service.encryptBytes(sample());
        assertThrows(EncryptionException.class, () -> service.decryptBytes(enc, "x".getBytes()));
    }
}
