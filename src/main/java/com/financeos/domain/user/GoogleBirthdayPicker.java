package com.financeos.domain.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Picks the birth date out of a People API {@code people/me?personFields=birthdays} response: the entry
 * whose {@code metadata.source.type} is ACCOUNT, else the primary one, else the first complete one.
 * Returns "yyyy-MM-dd", or "--MM-dd" when Google has no year.
 */
public final class GoogleBirthdayPicker {

    private GoogleBirthdayPicker() {
    }

    public static Optional<String> pick(ObjectMapper mapper, String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            return Optional.empty();
        }
        JsonNode birthdays = root.path("birthdays");
        if (!birthdays.isArray()) {
            return Optional.empty();
        }
        JsonNode account = null;
        JsonNode primary = null;
        JsonNode any = null;
        for (JsonNode entry : birthdays) {
            if (format(entry.path("date")) == null) {
                continue;
            }
            if (any == null) {
                any = entry;
            }
            if (account == null && "ACCOUNT".equals(entry.path("metadata").path("source").path("type").asText())) {
                account = entry;
            }
            if (primary == null && entry.path("metadata").path("primary").asBoolean(false)) {
                primary = entry;
            }
        }
        JsonNode chosen = account != null ? account : primary != null ? primary : any;
        return chosen == null ? Optional.empty() : Optional.of(format(chosen.path("date")));
    }

    private static String format(JsonNode date) {
        int month = date.path("month").asInt(0);
        int day = date.path("day").asInt(0);
        int year = date.path("year").asInt(0);
        if (month < 1 || day < 1) {
            return null;
        }
        return year > 0 ? String.format("%04d-%02d-%02d", year, month, day) : String.format("--%02d-%02d", month, day);
    }
}
