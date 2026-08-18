package com.financeos.core.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;

import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretMaskingTest {

    private LogstashEncoder encoder;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        logger = (Logger) LoggerFactory.getLogger(SecretMaskingTest.class);

        encoder = new LogstashEncoder();
        encoder.setContext(logger.getLoggerContext());
        encoder.setCustomFields("{\"service\":\"financeos-server\",\"env\":\"test\"}");

        // Load spring logback xml decorator configuration equivalent
        String jsonDecoratorConfig = """
                <jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
                    <defaultMask>****</defaultMask>
                    <valueMask><value>(?i)Bearer\\s+[A-Za-z0-9._\\-]+</value></valueMask>
                    <valueMask><value>AIza[0-9A-Za-z_\\-]{35}</value></valueMask>
                    <valueMask><value>sk-[A-Za-z0-9]{20,}</value></valueMask>
                    <valueMask><value>gsk_[A-Za-z0-9]{20,}</value></valueMask>
                    <valueMask><value>csk-[A-Za-z0-9]{20,}</value></valueMask>
                    <valueMask><value>(?i)"(access_token|refresh_token|id_token|client_secret)"\\s*:\\s*"[^"]+"</value></valueMask>
                    <valueMask><value>FINANCEOS_SESSION=[A-Za-z0-9\\-_]+</value></valueMask>
                    <valueMask><value>(?i)([?&amp;](api[_-]?key|key|token|access_token)=)[^&amp;\\s]+</value></valueMask>
                    <valueMask><value>//[^:/@\\s]+:[^@/\\s]+@</value></valueMask>
                </jsonGeneratorDecorator>
                """;

        net.logstash.logback.mask.MaskingJsonGeneratorDecorator decorator = new net.logstash.logback.mask.MaskingJsonGeneratorDecorator();
        decorator.setDefaultMask("****");

        // Set value mask targets
        decorator.addValue("(?i)Bearer\\s+[A-Za-z0-9._\\-]+");
        decorator.addValue("AIza[0-9A-Za-z_\\-]{35}");
        decorator.addValue("sk-[A-Za-z0-9]{20,}");
        decorator.addValue("gsk_[A-Za-z0-9]{20,}");
        decorator.addValue("csk-[A-Za-z0-9]{20,}");
        decorator.addValue("(?i)\"(access_token|refresh_token|id_token|client_secret)\"\\s*:\\s*\"[^\"]+\"");
        decorator.addValue("FINANCEOS_SESSION=[A-Za-z0-9\\-_]+");
        decorator.addValue("(?i)([?&](api[_-]?key|key|token|access_token)=)[^&\\s]+");
        decorator.addValue("//[^:/@\\s]+:[^@/\\s]+@");

        decorator.start();
        encoder.setJsonGeneratorDecorator(decorator);
        encoder.start();
    }

    private String encodeLog(String message) throws Exception {
        LoggingEvent event = new LoggingEvent("com.financeos.core.observability.SecretMaskingTest",
                logger, Level.INFO, message, null, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(encoder.encode(event));
        return baos.toString(StandardCharsets.UTF_8);
    }

    @Test
    void testMaskBearerToken() throws Exception {
        String log = encodeLog("Header Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertFalse(log.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskGoogleApiKey() throws Exception {
        String key = "AIzaSyD12345678901234567890123456789012"; // 4 + 35 = 39 chars
        String log = encodeLog("Google API key used: " + key);
        assertFalse(log.contains(key));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskOpenAiKey() throws Exception {
        String log = encodeLog("OpenAI Key: sk-proj12345678901234567890abcde");
        assertFalse(log.contains("sk-proj12345678901234567890abcde"));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskGroqKey() throws Exception {
        String log = encodeLog("Groq Key: gsk_12345678901234567890abcde");
        assertFalse(log.contains("gsk_12345678901234567890abcde"));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskCerebrasKey() throws Exception {
        String log = encodeLog("Cerebras Key: csk-12345678901234567890abcde");
        assertFalse(log.contains("csk-12345678901234567890abcde"));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskOAuthTokenJsonValues() throws Exception {
        String log = encodeLog("OAuth response: {\"access_token\": \"secret_access_123\", \"refresh_token\": \"secret_refresh_456\"}");
        assertFalse(log.contains("secret_access_123"));
        assertFalse(log.contains("secret_refresh_456"));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskSessionCookie() throws Exception {
        String log = encodeLog("Cookie: FINANCEOS_SESSION=abc123def456ghi789");
        assertFalse(log.contains("abc123def456ghi789"));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskCredentialsInUrls() throws Exception {
        String log = encodeLog("Requesting https://api.example.com/v1?api_key=secretKey123&other=value");
        assertFalse(log.contains("secretKey123"));
        assertTrue(log.contains("****"));
    }

    @Test
    void testMaskBasicAuthInUrls() throws Exception {
        String log = encodeLog("Connecting to https://user:pass123@db.example.com/data");
        assertFalse(log.contains("user:pass123"));
        assertTrue(log.contains("****"));
    }
}
