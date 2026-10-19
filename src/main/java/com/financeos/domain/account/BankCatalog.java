package com.financeos.domain.account;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The banks an account can belong to. {@code id} is the stored value ({@code accounts.bank}) and
 * is the same id the client uses; {@code aliases} are the names a bank goes by in email text.
 */
public final class BankCatalog {

    public record Bank(String id, String name, List<String> aliases) {}

    public static final List<Bank> BANKS = List.of(
            new Bank("sbi", "State Bank of India", List.of("State Bank of India", "SBI Card", "SBI")),
            new Bank("bank_of_baroda", "Bank of Baroda", List.of("Bank of Baroda")),
            new Bank("canara", "Canara Bank", List.of("Canara Bank")),
            new Bank("indian_bank", "Indian Bank", List.of("Indian Bank")),
            new Bank("hdfc", "HDFC Bank", List.of("HDFC Bank", "HDFC")),
            new Bank("icici", "ICICI Bank", List.of("ICICI Bank", "ICICI")),
            new Bank("axis", "Axis Bank", List.of("Axis Bank")),
            new Bank("indusind", "IndusInd Bank", List.of("IndusInd Bank", "IndusInd")),
            new Bank("yes", "Yes Bank", List.of("Yes Bank")),
            new Bank("idfc_first", "IDFC First Bank", List.of("IDFC First Bank", "IDFC FIRST", "IDFC")),
            new Bank("federal", "Federal Bank", List.of("Federal Bank")),
            new Bank("csb", "CSB Bank", List.of("CSB Bank", "Catholic Syrian Bank")),
            new Bank("au", "AU Small Finance Bank", List.of("AU Small Finance Bank", "AU Bank")),
            new Bank("equitas", "Equitas Small Finance Bank", List.of("Equitas Small Finance Bank", "Equitas")),
            new Bank("slice", "slice Small Finance Bank", List.of("slice Small Finance Bank", "North East Small Finance Bank", "slice")),
            new Bank("unity", "Unity Small Finance Bank", List.of("Unity Small Finance Bank", "Unity Bank")),
            new Bank("amex", "American Express", List.of("American Express", "Amex")),
            new Bank("hsbc", "HSBC", List.of("HSBC")),
            new Bank("sbm", "SBM Bank India", List.of("SBM Bank"))
    );

    /**
     * Names of banks that are not in the catalog but contain a catalog alias ("Indian Bank" inside
     * "South Indian Bank"): their text is consumed without naming any bank.
     */
    private static final List<String> NOT_CATALOGUED = List.of("South Indian Bank", "Indian Overseas Bank");

    private static final Map<String, Bank> BY_ID = BANKS.stream()
            .collect(Collectors.toUnmodifiableMap(Bank::id, Function.identity()));

    private record AliasPattern(String bankId, int length, Pattern pattern) {}

    /** Every alias of every bank, longest first, so "South Indian Bank" is consumed before "Indian Bank" can match inside it. */
    private static final List<AliasPattern> ALIAS_PATTERNS = java.util.stream.Stream.concat(
                    BANKS.stream().flatMap(b -> b.aliases().stream().map(a -> new AliasPattern(b.id(), a.length(), aliasPattern(a)))),
                    NOT_CATALOGUED.stream().map(a -> new AliasPattern(null, a.length(), aliasPattern(a))))
            .sorted(Comparator.comparingInt(AliasPattern::length).reversed())
            .toList();

    private BankCatalog() {}

    public static boolean isKnown(String id) {
        return id != null && BY_ID.containsKey(id);
    }

    public static Optional<Bank> find(String id) {
        return Optional.ofNullable(id == null ? null : BY_ID.get(id));
    }

    private static Pattern aliasPattern(String alias) {
        String body = Pattern.quote(alias.trim()).replace(" ", "\\E\\s+\\Q");
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + body + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * The ids of the banks named in {@code text} (case-insensitive, whole words, longest alias first;
     * text an alias already consumed cannot match a shorter alias of another bank).
     */
    public static Set<String> banksNamedIn(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            StringBuilder remaining = new StringBuilder(text);
            for (AliasPattern ap : ALIAS_PATTERNS) {
                Matcher m = ap.pattern().matcher(remaining);
                List<int[]> spans = new ArrayList<>();
                while (m.find()) {
                    spans.add(new int[] {m.start(), m.end()});
                }
                if (spans.isEmpty()) {
                    continue;
                }
                if (ap.bankId() != null) {
                    found.add(ap.bankId());
                }
                for (int[] s : spans) {
                    for (int i = s[0]; i < s[1]; i++) {
                        remaining.setCharAt(i, '\u0000');
                    }
                }
            }
        }
        return found;
    }
}
