package com.financeos.core.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push message encryption (RFC 8291, {@code aes128gcm} content coding, RFC 8188) and VAPID
 * request signing (RFC 8292), on JDK primitives only.
 *
 * <p>Deliberately hand-rolled: the usual Java web-push library pulls in Netty, an async HTTP
 * stack and BouncyCastle, which is far too heavy for the trimmed production JVM. Everything
 * needed is in {@code java.security} / {@code javax.crypto}: P-256 ECDH, HKDF built from
 * HMAC-SHA256, AES-128-GCM and ES256 (the P1363 signature format gives the raw {@code r||s}
 * that JWS wants without any DER parsing). {@link WebPushCryptoTest} checks the encryption
 * against the RFC 8291 Appendix A vector.
 */
public final class WebPushCrypto {

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final int RECORD_SIZE = 4096;
    private static final int GCM_TAG_BITS = 128;
    private static final int PUBLIC_KEY_LENGTH = 65;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Largest plaintext that fits one record: record size minus the padding delimiter and the GCM tag. */
    public static final int MAX_PLAINTEXT_BYTES = RECORD_SIZE - 1 - GCM_TAG_BITS / 8;

    private WebPushCrypto() {
    }

    // ---------------------------------------------------------------- encryption

    /**
     * Encrypts {@code plaintext} for the subscription identified by its {@code p256dh} public key
     * and {@code auth} secret, with a fresh ephemeral key pair and salt. Returns the complete
     * {@code aes128gcm} body (header + single record).
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublicRaw, byte[] authSecret) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublicRaw, authSecret, generateKeyPair(), salt);
    }

    /** Deterministic variant (ephemeral key pair and salt supplied) — used by the RFC vector test. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublicRaw, byte[] authSecret, KeyPair asKeyPair, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Push payload too large: " + plaintext.length + " bytes, max " + MAX_PLAINTEXT_BYTES);
        }
        if (salt.length != SALT_LENGTH) {
            throw new IllegalArgumentException("Salt must be 16 bytes");
        }
        try {
            ECPublicKey uaPublic = decodePublicKey(uaPublicRaw);
            byte[] asPublicRaw = encodePublicKey((ECPublicKey) asKeyPair.getPublic());

            byte[] ecdhSecret = ecdh(asKeyPair.getPrivate(), uaPublic);
            byte[] ikm = hkdf(authSecret, ecdhSecret, concat(KEY_INFO_PREFIX, uaPublicRaw, asPublicRaw), 32);
            byte[] cek = hkdf(salt, ikm, CEK_INFO, 16);
            byte[] nonce = hkdf(salt, ikm, NONCE_INFO, 12);

            byte[] padded = concat(plaintext, new byte[] {0x02});
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(padded);

            ByteBuffer header = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + PUBLIC_KEY_LENGTH);
            header.put(salt).putInt(RECORD_SIZE).put((byte) PUBLIC_KEY_LENGTH).put(asPublicRaw);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push encryption failed", e);
        }
    }

    // ---------------------------------------------------------------- VAPID

    /**
     * A signed VAPID JWT for {@code audience} (the push service origin), valid until {@code expiry}
     * (RFC 8292 caps this at 24 hours from now).
     */
    public static String vapidToken(String audience, String subject, Instant expiry, ECPrivateKey privateKey) {
        String header = base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64Url(("{\"aud\":\"" + audience + "\",\"exp\":" + expiry.getEpochSecond()
                + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        try {
            // P1363 format = raw r||s (64 bytes), exactly what JWS ES256 expects; no DER unwrapping.
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID signing failed", e);
        }
    }

    // ---------------------------------------------------------------- keys

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 key generation failed", e);
        }
    }

    /** Parses an uncompressed P-256 point ({@code 0x04 || X || Y}, 65 bytes). */
    public static ECPublicKey decodePublicKey(byte[] raw) {
        if (raw == null || raw.length != PUBLIC_KEY_LENGTH || raw[0] != 0x04) {
            throw new IllegalArgumentException("Expected a 65-byte uncompressed P-256 public key");
        }
        try {
            BigInteger x = new BigInteger(1, raw, 1, 32);
            BigInteger y = new BigInteger(1, raw, 33, 32);
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256()));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 public key", e);
        }
    }

    /** Serialises a P-256 public key as an uncompressed point (65 bytes). */
    public static byte[] encodePublicKey(ECPublicKey key) {
        ECPoint w = key.getW();
        byte[] out = new byte[PUBLIC_KEY_LENGTH];
        out[0] = 0x04;
        System.arraycopy(fixedLength(w.getAffineX(), 32), 0, out, 1, 32);
        System.arraycopy(fixedLength(w.getAffineY(), 32), 0, out, 33, 32);
        return out;
    }

    /** Parses a raw 32-byte P-256 private scalar (the format {@code web-push generate-vapid-keys} prints). */
    public static ECPrivateKey decodePrivateKey(byte[] scalar) {
        if (scalar == null || scalar.length != 32) {
            throw new IllegalArgumentException("Expected a 32-byte P-256 private key");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), p256()));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 private key", e);
        }
    }

    public static byte[] encodePrivateKey(ECPrivateKey key) {
        return fixedLength(key.getS(), 32);
    }

    public static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] base64UrlDecode(String text) {
        return Base64.getUrlDecoder().decode(text.trim());
    }

    // ---------------------------------------------------------------- primitives (package-private for tests)

    static byte[] ecdh(java.security.PrivateKey privateKey, ECPublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    /** RFC 5869 HKDF-SHA256, single output block (length ≤ 32). */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        if (length > 32) {
            throw new IllegalArgumentException("Single-block HKDF supports at most 32 bytes");
        }
        byte[] prk = hmac(salt, ikm);
        byte[] okm = hmac(prk, concat(info, new byte[] {0x01}));
        byte[] out = new byte[length];
        System.arraycopy(okm, 0, out, 0, length);
        return out;
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] fixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        if (raw.length > length) {
            // A leading sign byte from toByteArray(); the magnitude itself always fits.
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }
}
