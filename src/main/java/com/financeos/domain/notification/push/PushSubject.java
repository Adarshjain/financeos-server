package com.financeos.domain.notification.push;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the VAPID {@code sub} claim. Apple's push service answers 403 BadJwtToken for a subject on a
 * local or placeholder host, so a configured subject only counts when it is a {@code mailto:} or
 * {@code https:} URL on a real host; otherwise the app's public https client origin stands in.
 */
final class PushSubject {

    private static final Set<String> BAD_HOSTS = Set.of("localhost", "example", "invalid", "test", "local");

    private PushSubject() {
    }

    /** The configured subject when valid, else the first usable https origin of {@code originLists} (each a comma separated list), else the configured subject as it is. */
    static String resolve(String configured, List<String> originLists) {
        if (isValid(configured)) {
            return configured.trim();
        }
        return fallbackOrigin(originLists).orElse(configured);
    }

    /** True for a {@code mailto:} address or an {@code https:} URL whose host is not local, test or placeholder. */
    static boolean isValid(String subject) {
        if (subject == null || subject.isBlank()) {
            return false;
        }
        String s = subject.trim();
        String host;
        if (s.regionMatches(true, 0, "mailto:", 0, 7)) {
            int at = s.lastIndexOf('@');
            if (at < 8 || at == s.length() - 1) {
                return false;
            }
            host = s.substring(at + 1);
            int q = host.indexOf('?');
            host = q >= 0 ? host.substring(0, q) : host;
        } else if (s.regionMatches(true, 0, "https://", 0, 8)) {
            try {
                host = URI.create(s).getHost();
            } catch (IllegalArgumentException e) {
                return false;
            }
        } else {
            return false;
        }
        return host != null && !badHost(host);
    }

    private static boolean badHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty() || h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]") || !h.contains(".")) {
            return true;
        }
        String[] labels = h.split("\\.");
        if (labels.length < 2 || java.util.Arrays.stream(labels).anyMatch(String::isEmpty)) {
            return true;   // "a." or ".." and the like: not a real host
        }
        String tld = labels[labels.length - 1];
        // example.com / example.org / example.net and the reserved .test, .invalid, .local, .localhost, .example TLDs
        return BAD_HOSTS.contains(tld) || tld.equals("localhost") || labels[labels.length - 2].equals("example")
                || labels[0].equals("example");
    }

    private static Optional<String> fallbackOrigin(List<String> originLists) {
        for (String list : originLists) {
            if (list == null) {
                continue;
            }
            for (String raw : list.split(",")) {
                String origin = raw.trim();
                if (isValid(origin) && origin.regionMatches(true, 0, "https://", 0, 8)) {
                    try {
                        URI u = URI.create(origin);
                        return Optional.of("https://" + u.getHost());
                    } catch (IllegalArgumentException ignored) {
                        // not a URL: next one
                    }
                }
            }
        }
        return Optional.empty();
    }
}
