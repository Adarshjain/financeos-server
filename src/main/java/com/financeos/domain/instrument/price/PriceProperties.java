package com.financeos.domain.instrument.price;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    @Getter
    @Setter
    public static class ProviderProperties {
        private boolean enabled = true;
        private String baseUrl;
        private long timeoutMs = 30000L;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }
}
