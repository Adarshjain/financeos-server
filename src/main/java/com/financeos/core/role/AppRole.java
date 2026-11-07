package com.financeos.core.role;

import java.util.Locale;

/** Node role of this JVM: {@code all} (default, everything), {@code api} (no background work), {@code worker}. */
public enum AppRole {
    ALL, API, WORKER;

    public static AppRole parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "all" -> ALL;   // unset or blank APP_ROLE= keeps the single-node default
            case "api" -> API;
            case "worker" -> WORKER;
            default -> throw new IllegalStateException(
                    "Invalid app.role '" + value + "': expected one of all, api, worker");
        };
    }
}
