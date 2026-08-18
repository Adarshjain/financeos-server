package com.financeos.core.observability;

import com.financeos.core.security.AppConfigProperties;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

@Slf4j
@Component
public class StartupFingerprintLogger {

    private final Environment environment;
    private final AppConfigProperties appConfig;
    private final BuildProperties buildProperties;
    private final Flyway flyway;

    @Autowired
    public StartupFingerprintLogger(
            Environment environment,
            AppConfigProperties appConfig,
            @Autowired(required = false) BuildProperties buildProperties,
            @Autowired(required = false) Flyway flyway) {
        this.environment = environment;
        this.appConfig = appConfig;
        this.buildProperties = buildProperties;
        this.flyway = flyway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            logStartupFingerprint();
            logSuspectConfigWarnings();
        } catch (Exception e) {
            log.error("Failed to execute startup fingerprint logger", e);
        }
    }

    private void logStartupFingerprint() {
        String version = buildProperties != null ? buildProperties.getVersion() : "1.0.0";
        String gitSha = buildProperties != null ? buildProperties.get("gitSha") : "local";
        if (gitSha == null || gitSha.startsWith("${")) {
            gitSha = "local";
        }

        String[] activeProfiles = environment.getActiveProfiles();
        String profilesStr = activeProfiles.length > 0 ? String.join(",", activeProfiles) : "default";

        String javaVersion = System.getProperty("java.version");
        long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        String serverPort = environment.getProperty("server.port", "8080");
        String managementPort = environment.getProperty("management.server.port", serverPort);

        String jdbcUrl = environment.getProperty("spring.datasource.url", "");
        String[] dbDetails = parseDbHostAndService(jdbcUrl);
        String dbHost = dbDetails[0];
        String dbServiceName = dbDetails[1];

        String flywayVersion = "unknown";
        if (flyway != null && flyway.info() != null && flyway.info().current() != null) {
            flywayVersion = flyway.info().current().getVersion().getVersion();
        }

        String llmChain = environment.getProperty("llm.chain", "gemini,openai,groq,cerebras");
        boolean hasGeminiKey = isNotEmpty(environment.getProperty("gemini.api-key")) || isNotEmpty(environment.getProperty("GEMINI_API_KEY"));
        boolean hasOpenAiKey = isNotEmpty(environment.getProperty("openai.api-key")) || isNotEmpty(environment.getProperty("OPENAI_API_KEY"));
        boolean hasGroqKey = isNotEmpty(environment.getProperty("groq.api-key")) || isNotEmpty(environment.getProperty("GROQ_API_KEY"));
        boolean hasCerebrasKey = isNotEmpty(environment.getProperty("cerebras.api-key")) || isNotEmpty(environment.getProperty("CEREBRAS_API_KEY"));

        String gmailCron = environment.getProperty("gmail.ingest.cron", "0 0 10-22/2 * * *");
        String priceCron = environment.getProperty("price.refresh-cron", "0 0 19 * * *");

        boolean cookieSecure = Boolean.parseBoolean(environment.getProperty("server.servlet.session.cookie.secure",
                environment.getProperty("COOKIE_SECURE", "false")));
        String corsAllowedOrigins = appConfig != null && appConfig.getCors() != null ? appConfig.getCors().getAllowedOrigins() : "";
        String uiPath = environment.getProperty("app.ui-path", environment.getProperty("UI_PATH", "https://localhost:6970"));
        String googleRedirectUri = environment.getProperty("google.oauth.redirect-uri", environment.getProperty("GOOGLE_OAUTH_REDIRECT_URI", ""));
        String gmailRedirectUri = environment.getProperty("gmail.redirect-uri", environment.getProperty("GMAIL_REDIRECT_URI", ""));

        log.info("Application started: version={}, gitSha={}, profiles={}",
                version, gitSha, profilesStr,
                StructuredArguments.keyValue("event", Events.APP_STARTED),
                StructuredArguments.keyValue("version", version),
                StructuredArguments.keyValue("gitSha", gitSha),
                StructuredArguments.keyValue("activeProfiles", profilesStr),
                StructuredArguments.keyValue("javaVersion", javaVersion),
                StructuredArguments.keyValue("maxMemoryMb", maxMemoryMb),
                StructuredArguments.keyValue("serverPort", serverPort),
                StructuredArguments.keyValue("managementPort", managementPort),
                StructuredArguments.keyValue("dbHost", dbHost),
                StructuredArguments.keyValue("dbServiceName", dbServiceName),
                StructuredArguments.keyValue("flywayVersion", flywayVersion),
                StructuredArguments.keyValue("llmChain", llmChain),
                StructuredArguments.keyValue("hasGeminiKey", hasGeminiKey),
                StructuredArguments.keyValue("hasOpenAiKey", hasOpenAiKey),
                StructuredArguments.keyValue("hasGroqKey", hasGroqKey),
                StructuredArguments.keyValue("hasCerebrasKey", hasCerebrasKey),
                StructuredArguments.keyValue("gmailIngestCron", gmailCron),
                StructuredArguments.keyValue("priceRefreshCron", priceCron),
                StructuredArguments.keyValue("cookieSecure", cookieSecure),
                StructuredArguments.keyValue("corsAllowedOrigins", corsAllowedOrigins),
                StructuredArguments.keyValue("uiPath", uiPath),
                StructuredArguments.keyValue("googleRedirectUri", googleRedirectUri),
                StructuredArguments.keyValue("gmailRedirectUri", gmailRedirectUri)
        );
    }

    private void logSuspectConfigWarnings() {
        boolean cookieSecure = Boolean.parseBoolean(environment.getProperty("server.servlet.session.cookie.secure",
                environment.getProperty("COOKIE_SECURE", "false")));
        String corsAllowedOrigins = appConfig != null && appConfig.getCors() != null ? appConfig.getCors().getAllowedOrigins() : "";
        String encKey = appConfig != null && appConfig.getEncryption() != null ? appConfig.getEncryption().getKey() : "";
        String googleRedirectUri = environment.getProperty("google.oauth.redirect-uri", environment.getProperty("GOOGLE_OAUTH_REDIRECT_URI", ""));

        boolean hasGeminiKey = isNotEmpty(environment.getProperty("gemini.api-key")) || isNotEmpty(environment.getProperty("GEMINI_API_KEY"));
        boolean hasOpenAiKey = isNotEmpty(environment.getProperty("openai.api-key")) || isNotEmpty(environment.getProperty("OPENAI_API_KEY"));
        boolean hasGroqKey = isNotEmpty(environment.getProperty("groq.api-key")) || isNotEmpty(environment.getProperty("GROQ_API_KEY"));
        boolean hasCerebrasKey = isNotEmpty(environment.getProperty("cerebras.api-key")) || isNotEmpty(environment.getProperty("CEREBRAS_API_KEY"));

        // Check 1: COOKIE_SECURE=false while any CORS origin starts with https://
        if (!cookieSecure && Arrays.stream(corsAllowedOrigins.split(",")).map(String::trim).anyMatch(o -> o.startsWith("https://"))) {
            log.warn("Suspect configuration: COOKIE_SECURE is false while CORS allowed origins contain HTTPS endpoints ({})",
                    corsAllowedOrigins,
                    StructuredArguments.keyValue("event", Events.APP_CONFIG_SUSPECT),
                    StructuredArguments.keyValue("reason", "insecure-cookie-with-https-cors")
            );
        }

        // Check 2: app.encryption.key still starts with your-32-byte
        if (encKey != null && encKey.startsWith("your-32-byte")) {
            log.warn("Suspect configuration: app.encryption.key uses default placeholder",
                    StructuredArguments.keyValue("event", Events.APP_CONFIG_SUSPECT),
                    StructuredArguments.keyValue("reason", "default-encryption-key")
            );
        }

        // Check 3: zero LLM providers have a non-empty key
        if (!hasGeminiKey && !hasOpenAiKey && !hasGroqKey && !hasCerebrasKey) {
            log.warn("Suspect configuration: zero LLM providers have non-empty API keys configured",
                    StructuredArguments.keyValue("event", Events.APP_CONFIG_SUSPECT),
                    StructuredArguments.keyValue("reason", "no-llm-keys-configured")
            );
        }

        // Check 4: GOOGLE_OAUTH_REDIRECT_URI host is not among the CORS allowed origins
        if (isNotEmpty(googleRedirectUri)) {
            try {
                URI redirectUri = URI.create(googleRedirectUri);
                String redirectHost = redirectUri.getHost();
                if (redirectHost != null) {
                    boolean matched = Arrays.stream(corsAllowedOrigins.split(","))
                            .map(String::trim)
                            .filter(this::isNotEmpty)
                            .map(origin -> {
                                try {
                                    return URI.create(origin).getHost();
                                } catch (Exception e) {
                                    return origin;
                                }
                            })
                            .filter(Objects::nonNull)
                            .anyMatch(corsHost -> corsHost.equalsIgnoreCase(redirectHost));

                    if (!matched) {
                        log.warn("Suspect configuration: GOOGLE_OAUTH_REDIRECT_URI host ({}) not present in CORS allowed origins ({})",
                                redirectHost, corsAllowedOrigins,
                                StructuredArguments.keyValue("event", Events.APP_CONFIG_SUSPECT),
                                StructuredArguments.keyValue("reason", "oauth-redirect-cors-host-mismatch")
                        );
                    }
                }
            } catch (Exception ignored) {
                // Keep checks pure: never throw
            }
        }
    }

    private String[] parseDbHostAndService(String url) {
        if (url == null || url.isBlank()) {
            return new String[]{"unknown", "unknown"};
        }
        try {
            if (url.startsWith("jdbc:h2:")) {
                return new String[]{"localhost-h2", "h2-mem"};
            }
            if (url.contains("HOST=")) {
                String host = extractRegex(url, "(?i)HOST\\s*=\\s*([^\\)\\,\\s]+)");
                String service = extractRegex(url, "(?i)SERVICE_NAME\\s*=\\s*([^\\)\\,\\s]+)");
                return new String[]{host.isBlank() ? "oracle-host" : host, service.isBlank() ? "oracle-service" : service};
            }
            URI cleanUri = URI.create(url.replace("jdbc:", ""));
            String host = cleanUri.getHost() != null ? cleanUri.getHost() : "unknown";
            String path = cleanUri.getPath() != null ? cleanUri.getPath().replace("/", "") : "unknown";
            return new String[]{host, path};
        } catch (Exception e) {
            return new String[]{"unknown", "unknown"};
        }
    }

    private String extractRegex(String input, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean isNotEmpty(String str) {
        return str != null && !str.isBlank();
    }
}
