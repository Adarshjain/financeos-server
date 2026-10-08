package com.financeos.core.push;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** RFC 8291 Appendix A vector plus round trips — the encryption must match the spec byte for byte. */
class WebPushCryptoTest {

    private static final String PLAINTEXT = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String ECDH_SECRET = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
    private static final String IKM = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg";
    private static final String CEK = "oIhVW04MRdy2XN9CiKLxTg";
    private static final String NONCE = "4h_95klXJ5E_qnoN";
    private static final String HEADER = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String CIPHERTEXT = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    private static byte[] b64(String s) {
        return WebPushCrypto.base64UrlDecode(s);
    }

    private static KeyPair asKeyPair() {
        return new KeyPair(WebPushCrypto.decodePublicKey(b64(AS_PUBLIC)), WebPushCrypto.decodePrivateKey(b64(AS_PRIVATE)));
    }

    @Test
    void keyCodecRoundTripsTheRfcKeys() {
        ECPublicKey asPublic = WebPushCrypto.decodePublicKey(b64(AS_PUBLIC));
        assertEquals(AS_PUBLIC, WebPushCrypto.base64Url(WebPushCrypto.encodePublicKey(asPublic)));
        ECPrivateKey asPrivate = WebPushCrypto.decodePrivateKey(b64(AS_PRIVATE));
        assertEquals(AS_PRIVATE, WebPushCrypto.base64Url(WebPushCrypto.encodePrivateKey(asPrivate)));
    }

    @Test
    void intermediateValuesMatchRfc8291AppendixA() throws Exception {
        byte[] uaPublic = b64(UA_PUBLIC);
        byte[] asPublic = b64(AS_PUBLIC);
        byte[] ecdh = WebPushCrypto.ecdh(WebPushCrypto.decodePrivateKey(b64(AS_PRIVATE)), WebPushCrypto.decodePublicKey(uaPublic));
        assertEquals(ECDH_SECRET, WebPushCrypto.base64Url(ecdh));

        byte[] keyInfo = WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = WebPushCrypto.hkdf(b64(AUTH), ecdh, keyInfo, 32);
        assertEquals(IKM, WebPushCrypto.base64Url(ikm));

        byte[] cek = WebPushCrypto.hkdf(b64(SALT), ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        assertEquals(CEK, WebPushCrypto.base64Url(cek));
        byte[] nonce = WebPushCrypto.hkdf(b64(SALT), ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        assertEquals(NONCE, WebPushCrypto.base64Url(nonce));
    }

    @Test
    void encryptedBodyMatchesRfc8291Section5() {
        byte[] body = WebPushCrypto.encrypt(b64(PLAINTEXT), b64(UA_PUBLIC), b64(AUTH), asKeyPair(), b64(SALT));
        byte[] expected = WebPushCrypto.concat(b64(HEADER), b64(CIPHERTEXT));
        assertArrayEquals(expected, body);
    }

    @Test
    void randomKeysRoundTripThroughAReceiverSideDecrypt() throws Exception {
        KeyPair ua = WebPushCrypto.generateKeyPair();
        byte[] uaPublic = WebPushCrypto.encodePublicKey((ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        byte[] plaintext = "{\"title\":\"Bill due\",\"body\":\"₹12,345 by 15 Oct\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPushCrypto.encrypt(plaintext, uaPublic, auth);

        // Receiver side (RFC 8291 §3): same derivation with the UA private key and the sender's key from the header.
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int recordSize = java.nio.ByteBuffer.wrap(body, 16, 4).getInt();
        int idLen = body[20] & 0xff;
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLen);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLen, body.length);
        assertEquals(4096, recordSize);
        assertEquals(65, idLen);

        byte[] ecdh = WebPushCrypto.ecdh(ua.getPrivate(), WebPushCrypto.decodePublicKey(asPublic));
        byte[] ikm = WebPushCrypto.hkdf(auth, ecdh,
                WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic), 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);

        assertEquals(0x02, padded[padded.length - 1]);
        assertArrayEquals(plaintext, Arrays.copyOf(padded, padded.length - 1));
    }

    @Test
    void oversizedPayloadIsRejectedBeforeEncrypting() {
        byte[] big = new byte[WebPushCrypto.MAX_PLAINTEXT_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.encrypt(big, b64(UA_PUBLIC), b64(AUTH)));
        assertEquals(4079, WebPushCrypto.MAX_PLAINTEXT_BYTES);
    }

    @Test
    void malformedSubscriptionKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.decodePublicKey(new byte[10]));
        byte[] compressed = b64(UA_PUBLIC);
        compressed[0] = 0x02;
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.decodePublicKey(compressed));
        assertThrows(IllegalArgumentException.class, () -> WebPushCrypto.decodePrivateKey(new byte[31]));
    }

    @Test
    void vapidTokenIsAnEs256JwtVerifiableWithThePublicKey() throws Exception {
        KeyPair pair = WebPushCrypto.generateKeyPair();
        Instant expiry = Instant.parse("2026-10-08T12:00:00Z");

        String jwt = WebPushCrypto.vapidToken("https://fcm.googleapis.com", "mailto:ops@example.com", expiry, (ECPrivateKey) pair.getPrivate());

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        assertEquals("{\"typ\":\"JWT\",\"alg\":\"ES256\"}", new String(b64(parts[0]), StandardCharsets.UTF_8));
        assertEquals("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":" + expiry.getEpochSecond() + ",\"sub\":\"mailto:ops@example.com\"}",
                new String(b64(parts[1]), StandardCharsets.UTF_8));
        assertEquals(64, b64(parts[2]).length);

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(b64(parts[2])));

        verifier.initVerify(WebPushCrypto.generateKeyPair().getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertFalse(verifier.verify(b64(parts[2])));
    }
}
