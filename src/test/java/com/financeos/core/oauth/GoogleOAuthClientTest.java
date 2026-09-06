package com.financeos.core.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.auth.dto.GoogleTokenResponse;
import com.financeos.api.auth.dto.GoogleUserInfo;
import com.financeos.core.exception.ValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the SSO client against a local HTTP server so the configured token / userinfo URLs are
 * proven to be the ones actually called (the e2e profile relies on this to play Google via WireMock).
 */
class GoogleOAuthClientTest {

    private record Seen(String method, String path, String authorization, String body) {}

    private static HttpServer server;
    private static String base;
    private static final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/google/oauth2/token", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"access_token\":\"AT-1\",\"expires_in\":3600,\"refresh_token\":\"RT-1\","
                    + "\"scope\":\"openid email\",\"token_type\":\"Bearer\",\"id_token\":\"x\"}");
        });
        server.createContext("/google/oauth2/token-broken", exchange -> {
            record(exchange);
            respond(exchange, 401, "{\"error\":\"invalid_grant\"}");
        });
        server.createContext("/google/oauth2/v2/userinfo", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"id\":\"sub-1\",\"email\":\"sso@example.test\",\"name\":\"SSO User\","
                    + "\"picture\":\"http://pic\",\"verified_email\":true}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        seen.clear();
    }

    private static void record(HttpExchange exchange) throws IOException {
        seen.add(new Seen(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static GoogleOAuthClient client(GoogleOAuthProperties urls) {
        return new GoogleOAuthClient("cid", "csecret", "http://localhost:6970/auth/google/callback", urls,
                new ObjectMapper());
    }

    private static GoogleOAuthProperties localUrls() {
        GoogleOAuthProperties urls = new GoogleOAuthProperties();
        urls.setAuthorizationUrl(base + "/google/o/oauth2/v2/auth");
        urls.setTokenUrl(base + "/google/oauth2/token");
        urls.setUserInfoUrl(base + "/google/oauth2/v2/userinfo");
        urls.setRevokeUrl(base + "/google/oauth2/revoke");
        return urls;
    }

    @Test
    void authorizationUrlUsesDefaultGoogleEndpointWhenNothingIsOverridden() {
        String url = client(new GoogleOAuthProperties()).buildAuthorizationUrl("abc.def");

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A6970%2Fauth%2Fgoogle%2Fcallback"), url);
        assertTrue(url.contains("scope=openid+email+profile+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly"), url);
        assertTrue(url.contains("state=abc.def"), url);
        assertTrue(url.endsWith("access_type=offline&prompt=consent"), url);
    }

    @Test
    void authorizationUrlHonoursOverride() {
        String url = client(localUrls()).buildAuthorizationUrl("s");
        assertTrue(url.startsWith(base + "/google/o/oauth2/v2/auth?client_id=cid&"), url);
    }

    @Test
    void tokenExchangePostsTheFormToTheConfiguredTokenUrl() {
        GoogleTokenResponse tokens = client(localUrls()).exchangeCodeForTokens("e2e-code-1");

        assertEquals("AT-1", tokens.accessToken());
        assertEquals("RT-1", tokens.refreshToken());
        assertEquals(3600L, tokens.expiresIn());
        assertEquals(1, seen.size());
        Seen call = seen.get(0);
        assertEquals("POST", call.method());
        assertEquals("/google/oauth2/token", call.path());
        assertEquals("code=e2e-code-1&client_id=cid&client_secret=csecret"
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A6970%2Fauth%2Fgoogle%2Fcallback"
                + "&grant_type=authorization_code", call.body());
    }

    @Test
    void tokenExchangeNon200BecomesValidationException() {
        GoogleOAuthProperties urls = localUrls();
        urls.setTokenUrl(base + "/google/oauth2/token-broken");

        ValidationException ex = assertThrows(ValidationException.class,
                () -> client(urls).exchangeCodeForTokens("bad"));
        assertTrue(ex.getMessage().contains("invalid_grant"), ex.getMessage());
    }

    @Test
    void userInfoGetsTheConfiguredUrlWithBearerToken() {
        GoogleUserInfo info = client(localUrls()).getUserInfo("AT-1");

        assertEquals("sub-1", info.id());
        assertEquals("sso@example.test", info.email());
        assertEquals("SSO User", info.name());
        assertEquals("http://pic", info.pictureUrl());
        assertTrue(info.verifiedEmail());
        Seen call = seen.get(0);
        assertEquals("GET", call.method());
        assertEquals("/google/oauth2/v2/userinfo", call.path());
        assertEquals("Bearer AT-1", call.authorization());
    }
}
