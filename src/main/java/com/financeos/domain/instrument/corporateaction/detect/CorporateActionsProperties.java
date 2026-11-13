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
        /** Total time allowed to read one notice document or feed body, on top of the size caps. */
        private long bodyDeadlineMs = 60_000;
        /** The exchange's archive host: notice PDFs, the equity list and the rename list. */
        private String archivesUrl = DEFAULT_ARCHIVES;
        /** The exchange's equity master list (CSV); null = under {@link #archivesUrl}. */
        private String listingsUrl;
        /** The exchange's list of company renames (CSV); null = under {@link #archivesUrl}. */
        private String nameChangesUrl;

        public static final String DEFAULT_ARCHIVES = "https://nsearchives.nseindia.com";

        private String archives() {
            return archivesUrl == null || archivesUrl.isBlank() ? DEFAULT_ARCHIVES : archivesUrl.replaceAll("/+$", "");
        }

        /** The equity list URL: the explicit setting, else {@code <archives>/content/equities/EQUITY_L.csv}. */
        public String getListingsUrl() {
            return listingsUrl == null || listingsUrl.isBlank() ? archives() + "/content/equities/EQUITY_L.csv" : listingsUrl;
        }

        /** The rename list URL: the explicit setting, else {@code <archives>/content/equities/namechange.csv}. */
        public String getNameChangesUrl() {
            return nameChangesUrl == null || nameChangesUrl.isBlank() ? archives() + "/content/equities/namechange.csv" : nameChangesUrl;
        }

        /** True when {@code url} is http(s) on the configured archives host: the only host notice documents are fetched from. */
        public boolean isArchiveLink(String url) {
            try {
                java.net.URI u = java.net.URI.create(url == null ? "" : url.trim());
                java.net.URI a = java.net.URI.create(archives());
                return u.getHost() != null && a.getHost() != null && u.getHost().equalsIgnoreCase(a.getHost())
                        && ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
                        && (u.getPort() == a.getPort());
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** A notice link on the real archive host moved to the configured archive host (a test server); other links as they are. */
        public String archiveLink(String url) {
            String real = DEFAULT_ARCHIVES;
            if (url != null && url.startsWith(real) && !archives().equals(real)) {
                return archives() + url.substring(real.length());
            }
            return url;
        }
    }
}
