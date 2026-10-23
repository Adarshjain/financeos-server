package com.financeos.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * A software WebAuthn platform authenticator for tests: P-256 key, hand-built authenticatorData, "none"
 * attestation as hand-encoded CBOR. Produces the same JSON a browser's {@code PublicKeyCredential.toJSON()}
 * does, so it can be posted to the app-lock endpoints as-is.
 */
public final class TestAuthenticator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final int FLAG_UP = 0x01;
    private static final int FLAG_UV = 0x04;
    private static final int FLAG_AT = 0x40;

    private final String rpId;
    private final String origin;
    private final KeyPair keys;
    private final byte[] credentialId = new byte[32];
    private byte[] userHandle;
    private long signCount;

    public TestAuthenticator(String rpId, String origin) {
        this.rpId = rpId;
        this.origin = origin;
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            this.keys = gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new SecureRandom().nextBytes(credentialId);
    }

    public String credentialId() {
        return B64.encodeToString(credentialId);
    }

    /** Answers {@code {publicKey: ...}} creation options with a new passkey. */
    public JsonNode create(JsonNode creationOptions, boolean userVerified) {
        JsonNode pk = creationOptions.get("publicKey");
        userHandle = B64D.decode(pk.get("user").get("id").asText());
        String clientData = clientData("webauthn.create", pk.get("challenge").asText());
        ByteArrayOutputStream authData = new ByteArrayOutputStream();
        authData.writeBytes(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        authData.write(FLAG_UP | FLAG_AT | (userVerified ? FLAG_UV : 0));
        authData.writeBytes(ByteBuffer.allocate(4).putInt((int) signCount).array());
        authData.writeBytes(new byte[16]); // aaguid
        authData.writeBytes(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
        authData.writeBytes(credentialId);
        authData.writeBytes(coseKey());

        ByteArrayOutputStream att = new ByteArrayOutputStream();
        att.write(0xA3); // map(3)
        att.writeBytes(text("fmt"));
        att.writeBytes(text("none"));
        att.writeBytes(text("attStmt"));
        att.write(0xA0); // empty map
        att.writeBytes(text("authData"));
        att.writeBytes(bytes(authData.toByteArray()));

        ObjectNode response = MAPPER.createObjectNode();
        response.put("attestationObject", B64.encodeToString(att.toByteArray()));
        response.put("clientDataJSON", B64.encodeToString(clientData.getBytes(StandardCharsets.UTF_8)));
        response.putArray("transports").add("internal");
        return credential(response);
    }

    /** Answers {@code {publicKey: ...}} request options with an assertion. */
    public JsonNode get(JsonNode requestOptions, boolean userVerified) {
        JsonNode pk = requestOptions.get("publicKey");
        String clientData = clientData("webauthn.get", pk.get("challenge").asText());
        signCount++;
        ByteArrayOutputStream authData = new ByteArrayOutputStream();
        authData.writeBytes(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        authData.write(FLAG_UP | (userVerified ? FLAG_UV : 0));
        authData.writeBytes(ByteBuffer.allocate(4).putInt((int) signCount).array());

        byte[] signed = concat(authData.toByteArray(), sha256(clientData.getBytes(StandardCharsets.UTF_8)));
        ObjectNode response = MAPPER.createObjectNode();
        response.put("authenticatorData", B64.encodeToString(authData.toByteArray()));
        response.put("clientDataJSON", B64.encodeToString(clientData.getBytes(StandardCharsets.UTF_8)));
        response.put("signature", B64.encodeToString(sign(signed)));
        if (userHandle != null) {
            response.put("userHandle", B64.encodeToString(userHandle));
        }
        return credential(response);
    }

    private JsonNode credential(ObjectNode response) {
        ObjectNode cred = MAPPER.createObjectNode();
        cred.put("id", credentialId());
        cred.put("rawId", credentialId());
        cred.put("type", "public-key");
        cred.set("response", response);
        cred.putObject("clientExtensionResults");
        return cred;
    }

    private String clientData(String type, String challenge) {
        return "{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}";
    }

    private byte[] sign(byte[] data) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(keys.getPrivate());
            sig.update(data);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** COSE_Key for ES256: {1: 2, 3: -7, -1: 1, -2: x, -3: y}. */
    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xA5);
        out.write(0x01); out.write(0x02);
        out.write(0x03); out.write(0x26);
        out.write(0x20); out.write(0x01);
        out.write(0x21); out.writeBytes(bytes(fixed32(pub.getW().getAffineX())));
        out.write(0x22); out.writeBytes(bytes(fixed32(pub.getW().getAffineY())));
        return out.toByteArray();
    }

    private static byte[] fixed32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] text(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return concat(header(0x60, b.length), b);
    }

    private static byte[] bytes(byte[] b) {
        return concat(header(0x40, b.length), b);
    }

    private static byte[] header(int major, int len) {
        if (len < 24) {
            return new byte[] {(byte) (major | len)};
        }
        if (len < 256) {
            return new byte[] {(byte) (major | 24), (byte) len};
        }
        return new byte[] {(byte) (major | 25), (byte) (len >> 8), (byte) len};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
