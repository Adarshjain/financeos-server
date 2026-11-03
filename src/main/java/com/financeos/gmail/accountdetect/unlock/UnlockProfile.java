package com.financeos.gmail.accountdetect.unlock;

import java.util.List;
import java.util.Map;

/**
 * What a statement password can be built from: the user's name and birth date (as stored: "yyyy-MM-dd" or "--MM-dd")
 * and the statement passwords the user saved on their accounts, by bank id. Lives for one call; never logged.
 */
public record UnlockProfile(String displayName, String birthDate, Map<String, List<String>> savedByBank) {

    public UnlockProfile {
        savedByBank = savedByBank == null ? Map.of() : savedByBank;
    }

    public static UnlockProfile of(String displayName, String birthDate) {
        return new UnlockProfile(displayName, birthDate, Map.of());
    }

    @Override
    public String toString() {
        return "UnlockProfile[redacted]";
    }
}
