package com.financeos.domain.instrument.price;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "price")
@Getter
@Setter
public class PriceProperties {

    private boolean enabled = true;
    private String refreshCron = "0 0 19 * * *";
    private String timezone = "Asia/Kolkata";
    private Map<String, ProviderProperties> providers = new HashMap<>();
    private History history = new History();
    private Mfapi mfapi = new Mfapi();

    /** Price-history backfill (on first hold) and the monthly feed-price prune. */
    @Getter
    @Setter
    public static class History {
        private boolean enabled = true;
        /** The backfill never reaches further back than this, and the prune drops feed rows older than it. */
        private int maxYears = 2;
        /** The backfill reaches back at least this far, even for a recent first trade. */
        private int minYears = 1;
        private String cleanupCron = "0 30 3 1 * *";
    }

    /** mfapi.in: full NAV history per AMFI scheme code. */
    @Getter
    @Setter
    public static class Mfapi {
        private String baseUrl = "https://api.mfapi.in";
        private long timeoutMs = 30000L;
    }

    @Getter
    @Setter
    public static class ProviderProperties {
        private boolean enabled = true;
        private boolean batchEnabled = true;
        private int batchSize = 50;
        private String baseUrl = "https://query2.finance.yahoo.com";
        private String crumbBaseUrl = "https://fc.yahoo.com";
        private String crumbFallbackUrl = "https://finance.yahoo.com";
        private boolean fallbackEnabled = true;
        private long timeoutMs = 30000L;
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";
        /** Default pause after an HTTP 429 when Yahoo sends no usable Retry-After. */
        private Duration rateLimitCooldown = Duration.ofMinutes(15);
    }
}
