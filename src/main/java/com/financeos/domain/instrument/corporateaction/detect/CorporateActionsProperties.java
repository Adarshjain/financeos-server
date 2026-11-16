package com.financeos.domain.instrument.corporateaction.detect;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

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
        private String baseUrl = DEFAULT_BASE;
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

        public static final String DEFAULT_BASE = "https://www.nseindia.in";
        public static final String DEFAULT_ARCHIVES = "https://nsearchives.nseindia.in";
        /** The exchange's hosts, old and new: always allowed (links, redirects) next to the configured base and archives hosts. */
        private static final Set<String> EXCHANGE_HOSTS = Set.of("www.nseindia.com", "www.nseindia.in",
                "nsearchives.nseindia.com", "nsearchives.nseindia.in");
        private static final Set<String> EXCHANGE_ARCHIVE_HOSTS = Set.of("nsearchives.nseindia.com", "nsearchives.nseindia.in");

        private String archives() {
            return archivesUrl == null || archivesUrl.isBlank() ? DEFAULT_ARCHIVES : archivesUrl.replaceAll("/+$", "");
        }

        private static String hostOf(String url) {
            try {
                String h = java.net.URI.create(url == null ? "" : url.trim()).getHost();
                return h == null ? null : h.toLowerCase(java.util.Locale.ROOT);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Hosts a request may be redirected to: the exchange's own hosts plus the configured base and archives hosts. */
        public Set<String> allowedHosts() {
            Set<String> hosts = new java.util.HashSet<>(EXCHANGE_HOSTS);
            for (String h : new String[] {hostOf(baseUrl), hostOf(archives())}) {
                if (h != null) {
                    hosts.add(h);
                }
            }
            return hosts;
        }

        /** True when a redirect may go to {@code target}: https, the default port and an allowed host. */
        public boolean isAllowedRedirect(java.net.URI target) {
            return target != null && "https".equalsIgnoreCase(target.getScheme()) && target.getHost() != null
                    && (target.getPort() == -1 || target.getPort() == 443)
                    && allowedHosts().contains(target.getHost().toLowerCase(java.util.Locale.ROOT));
        }

        /** The equity list URL: the explicit setting, else {@code <archives>/content/equities/EQUITY_L.csv}. */
        public String getListingsUrl() {
            return listingsUrl == null || listingsUrl.isBlank() ? archives() + "/content/equities/EQUITY_L.csv" : listingsUrl;
        }

        /** The rename list URL: the explicit setting, else {@code <archives>/content/equities/namechange.csv}. */
        public String getNameChangesUrl() {
            return nameChangesUrl == null || nameChangesUrl.isBlank() ? archives() + "/content/equities/namechange.csv" : nameChangesUrl;
        }

        /**
         * True when {@code url} is on an archives host: the configured one (same scheme and port, so a test server works) or
         * one of the exchange's two archive hosts over https. The only hosts notice documents are fetched from.
         */
        public boolean isArchiveLink(String url) {
            try {
                java.net.URI u = java.net.URI.create(url == null ? "" : url.trim());
                java.net.URI a = java.net.URI.create(archives());
                if (u.getHost() == null || u.getScheme() == null) {
                    return false;
                }
                if (a.getHost() != null && u.getHost().equalsIgnoreCase(a.getHost()) && u.getScheme().equalsIgnoreCase(a.getScheme())
                        && u.getPort() == a.getPort()) {
                    return true;
                }
                return "https".equalsIgnoreCase(u.getScheme()) && (u.getPort() == -1 || u.getPort() == 443)
                        && EXCHANGE_ARCHIVE_HOSTS.contains(u.getHost().toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** A notice link on a real exchange archive host moved to the configured archive host when that is a test server; other links as they are. */
        public String archiveLink(String url) {
            String configured = archives();
            if (url == null || EXCHANGE_ARCHIVE_HOSTS.contains(hostOf(configured))) {
                return url;
            }
            for (String host : EXCHANGE_ARCHIVE_HOSTS) {
                String real = "https://" + host;
                if (url.startsWith(real)) {
                    return configured + url.substring(real.length());
                }
            }
            return url;
        }
    }
}
