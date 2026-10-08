package com.financeos.domain.notification.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.push.WebPushCrypto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebPushSenderTest {

    private PushProperties properties;
    private HttpClient httpClient;
    private WebPushSender sender;
    private PushSubscription subscription;
    private final PushMessage message = new PushMessage("Bill due", "₹1,000 by 28 Oct", "/dashboard", "bill-1");

    @BeforeEach
    void setUp() {
        KeyPair vapid = WebPushCrypto.generateKeyPair();
        properties = new PushProperties();
        properties.setVapidPublicKey(WebPushCrypto.base64Url(WebPushCrypto.encodePublicKey((ECPublicKey) vapid.getPublic())));
        properties.setVapidPrivateKey(WebPushCrypto.base64Url(WebPushCrypto.encodePrivateKey((ECPrivateKey) vapid.getPrivate())));
        properties.setSubject("mailto:ops@example.com");
        httpClient = mock(HttpClient.class);
        sender = new WebPushSender(properties, new ObjectMapper(), httpClient);

        KeyPair ua = WebPushCrypto.generateKeyPair();
        subscription = new PushSubscription("https://fcm.googleapis.com/fcm/send/abc123",
                WebPushCrypto.base64Url(WebPushCrypto.encodePublicKey((ECPublicKey) ua.getPublic())),
                WebPushCrypto.base64Url(new byte[16]), "Chrome", Instant.now());
    }

    @SuppressWarnings("unchecked")
    private void respond(int status) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn("");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @Test
    void sendsAnAes128gcmBodyWithVapidHeaders() throws Exception {
        respond(201);

        WebPushSender.SendResult result = sender.send(subscription, message);

        assertTrue(result.ok());
        assertFalse(result.gone());
        assertEquals(201, result.status());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("POST", request.method());
        assertEquals(URI.create(subscription.endpoint()), request.uri());
        assertEquals("aes128gcm", request.headers().firstValue("Content-Encoding").orElseThrow());
        assertEquals("application/octet-stream", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("86400", request.headers().firstValue("TTL").orElseThrow());
        String authorization = request.headers().firstValue("Authorization").orElseThrow();
        assertTrue(authorization.startsWith("vapid t="));
        assertTrue(authorization.endsWith(", k=" + properties.getVapidPublicKey()));
        long bodyLength = request.bodyPublisher().orElseThrow().contentLength();
        assertTrue(bodyLength > 86, "header (86 bytes) plus ciphertext");
    }

    @Test
    void goneAndFailureOutcomes() throws Exception {
        respond(410);
        assertTrue(sender.send(subscription, message).gone());
        respond(404);
        assertTrue(sender.send(subscription, message).gone());
        respond(500);
        WebPushSender.SendResult failed = sender.send(subscription, message);
        assertFalse(failed.ok());
        assertFalse(failed.gone());
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("boom"));
        assertFalse(sender.send(subscription, message).ok());
    }

    @Test
    void unconfiguredSenderNeverTalksToTheNetwork() throws Exception {
        PushProperties empty = new PushProperties();
        WebPushSender off = new WebPushSender(empty, new ObjectMapper(), httpClient);
        assertFalse(off.isConfigured());
        assertEquals("", off.publicKey());
        assertFalse(off.send(subscription, message).ok());
        verify(httpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void audienceIsTheEndpointOrigin() {
        assertEquals("https://fcm.googleapis.com", WebPushSender.origin(URI.create("https://fcm.googleapis.com/fcm/send/abc")));
        assertEquals("https://updates.push.services.mozilla.com",
                WebPushSender.origin(URI.create("https://updates.push.services.mozilla.com/wpush/v2/x")));
        assertEquals("http://localhost:9999", WebPushSender.origin(URI.create("http://localhost:9999/push/1")));
    }
}
