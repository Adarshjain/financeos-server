package com.financeos.domain.instrument.corporateaction.detect;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Settings of the automatic corporate-action detection ({@code corporate-actions.*}). */
@Component
@ConfigurationProperties(prefix = "corporate-actions")
@Getter
@Setter
public class CorporateActionsProperties {

    private boolean enabled = true;
    private String nightlyCron = "0 40 2 * * *";
    private String zone = "Asia/Kolkata";
    private Nse nse = new Nse();

    /** The exchange's public corporate-action feed. */
    @Getter
    @Setter
    public static class Nse {
        private String baseUrl = "https://www.nseindia.com";
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/121.0.0.0 Safari/537.36";
        private int timeoutMs = 30000;
        /** The exchange's equity master list (CSV); new listings carry their listing date. */
        private String listingsUrl = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv";
    }
}
