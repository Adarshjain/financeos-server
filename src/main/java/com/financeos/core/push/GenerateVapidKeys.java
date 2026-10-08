package com.financeos.core.push;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * One-off helper: prints a fresh VAPID key pair in the raw base64url form the app expects
 * ({@code PUSH_VAPID_PUBLIC_KEY} / {@code PUSH_VAPID_PRIVATE_KEY}). Same format as
 * {@code npx web-push generate-vapid-keys}, so either tool works.
 *
 * <pre>
 * ./mvnw -q compile exec:java -Dexec.mainClass=com.financeos.core.push.GenerateVapidKeys
 * </pre>
 */
public final class GenerateVapidKeys {

    private GenerateVapidKeys() {
    }

    public static void main(String[] args) {
        KeyPair pair = WebPushCrypto.generateKeyPair();
        System.out.println("PUSH_VAPID_PUBLIC_KEY=" + WebPushCrypto.base64Url(WebPushCrypto.encodePublicKey((ECPublicKey) pair.getPublic())));
        System.out.println("PUSH_VAPID_PRIVATE_KEY=" + WebPushCrypto.base64Url(WebPushCrypto.encodePrivateKey((ECPrivateKey) pair.getPrivate())));
    }
}
