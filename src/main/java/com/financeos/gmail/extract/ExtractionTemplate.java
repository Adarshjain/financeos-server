package com.financeos.gmail.extract;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One real email layout of one sender, loaded from YAML. {@code direction} is fixed, or null when it is
 * read from the {@code direction} group through {@code directionWords} (lower-case words per direction).
 */
public record ExtractionTemplate(
        String id,
        List<String> senders,
        Pattern subject,
        Pattern body,
        Direction direction,
        Map<Direction, Set<String>> directionWords,
        List<String> dateFormats,
        String currency,
        List<Pattern> counterpartyStrip
) {
    /** Whether the From header's domain is one of the template's sender domain fragments. */
    public boolean matchesSender(String domain) {
        if (domain == null) {
            return false;
        }
        for (String s : senders) {
            if (domain.equals(s) || domain.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }
}
