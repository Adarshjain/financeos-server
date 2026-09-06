package com.financeos.domain.instrument.price;

import java.util.List;

public final class YahooHosts {

    private YahooHosts() {}

    public static List<String> getCandidateHosts(PriceProperties.ProviderProperties props) {
        String primaryBaseUrl = props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                ? props.getBaseUrl()
                : "https://query2.finance.yahoo.com";
        boolean fallbackEnabled = props == null || props.isFallbackEnabled();
        if (!fallbackEnabled) {
            return List.of(primaryBaseUrl);
        }
        String fallbackUrl = primaryBaseUrl.contains("query2")
                ? "https://query1.finance.yahoo.com"
                : "https://query2.finance.yahoo.com";
        return List.of(primaryBaseUrl, fallbackUrl);
    }
}
