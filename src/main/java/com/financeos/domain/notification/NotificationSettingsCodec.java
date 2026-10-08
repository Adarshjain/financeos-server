package com.financeos.domain.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.domain.notification.push.PushSubscription;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The three small encodings on {@link UserNotificationSettings}: offsets CSV, kinds JSON map and
 * the subscriptions JSON array. Pure and lenient on read (bad stored data degrades to defaults
 * rather than breaking every notification for the user).
 */
public final class NotificationSettingsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<List<PushSubscription>> SUBSCRIPTIONS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Boolean>> KINDS = new TypeReference<>() {
    };

    private NotificationSettingsCodec() {
    }

    /** Descending, distinct, non-negative; falls back to the defaults when the CSV is unusable. */
    public static List<Integer> parseOffsets(String csv) {
        if (csv == null || csv.isBlank()) {
            return parseOffsets(UserNotificationSettings.DEFAULT_OFFSETS);
        }
        try {
            List<Integer> offsets = Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .filter(n -> n >= 0)
                    .distinct()
                    .sorted((a, b) -> Integer.compare(b, a))
                    .collect(Collectors.toList());
            return offsets.isEmpty() ? parseOffsets(UserNotificationSettings.DEFAULT_OFFSETS) : offsets;
        } catch (NumberFormatException e) {
            return parseOffsets(UserNotificationSettings.DEFAULT_OFFSETS);
        }
    }

    public static String writeOffsets(List<Integer> offsets) {
        return offsets.stream()
                .distinct()
                .sorted((a, b) -> Integer.compare(b, a))
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /** Every kind present; stored overrides win over {@link NotificationKind#defaultEnabled()}. */
    public static Map<NotificationKind, Boolean> parseKinds(String json) {
        Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationKind.class);
        for (NotificationKind kind : NotificationKind.values()) {
            kinds.put(kind, kind.defaultEnabled());
        }
        if (json == null || json.isBlank()) {
            return kinds;
        }
        try {
            Map<String, Boolean> stored = MAPPER.readValue(json, KINDS);
            for (Map.Entry<String, Boolean> entry : stored.entrySet()) {
                try {
                    NotificationKind kind = NotificationKind.valueOf(entry.getKey());
                    if (entry.getValue() != null) {
                        kinds.put(kind, entry.getValue());
                    }
                } catch (IllegalArgumentException ignored) {
                    // a kind that no longer exists
                }
            }
        } catch (Exception ignored) {
            // unreadable JSON: defaults
        }
        return kinds;
    }

    public static String writeKinds(Map<NotificationKind, Boolean> kinds) {
        try {
            Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            kinds.forEach((k, v) -> out.put(k.name(), v));
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<PushSubscription> parseSubscriptions(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<PushSubscription> list = MAPPER.readValue(json, SUBSCRIPTIONS);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** {@code null} for an empty list so the "has subscriptions" query stays a plain IS NOT NULL. */
    public static String writeSubscriptions(List<PushSubscription> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(subscriptions);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
