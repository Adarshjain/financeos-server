package com.financeos.domain.account;

import com.financeos.gmail.accountdetect.unlock.PasswordRender;
import com.financeos.gmail.accountdetect.unlock.StatementPasswordRules;

import java.util.List;
import java.util.Optional;

/** Builds a credit card's statement password from the profile and the card's last 6 digits, by the bank's CARD6 rule. */
public final class CardStatementPassword {

    private static final String CARD6 = "CARD6";

    private CardStatementPassword() {
    }

    /** The first password a CARD6 rule of the bank's credit-card patterns gives; empty when there is none or an input is missing. */
    public static Optional<String> derive(String bank, String displayName, String birthDate, String last6) {
        if (bank == null || last6 == null || last6.isBlank()) return Optional.empty();
        for (String pattern : StatementPasswordRules.bundled().patterns(bank, "credit_card")) {
            if (!List.of(pattern.split("\\+")).contains(CARD6)) continue;
            List<PasswordRender.Candidate> out = PasswordRender.render(pattern, displayName, birthDate, List.of(), List.of(last6.trim()));
            if (!out.isEmpty()) return Optional.of(out.get(0).password());
        }
        return Optional.empty();
    }
}
