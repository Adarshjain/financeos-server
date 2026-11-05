package com.financeos.domain.nps.statement;

import java.time.LocalDate;
import java.util.List;

/** Protean NPS statement password: first 4 letters of the first name (lower case, '9'-padded) + DDMM of birth. */
public final class NpsStatementPassword {

    private NpsStatementPassword() {}

    public static List<String> candidates(String displayName, LocalDate birthDate) {
        if (displayName == null || birthDate == null) {
            return List.of();
        }
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String first = trimmed.split("\\s+")[0].replaceAll("[^A-Za-z]", "").toLowerCase();
        if (first.length() > 4) {
            first = first.substring(0, 4);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = first.length(); i < 4; i++) {
            sb.append('9');
        }
        sb.append(first);
        sb.append(String.format("%02d%02d", birthDate.getDayOfMonth(), birthDate.getMonthValue()));
        return List.of(sb.toString());
    }
}
