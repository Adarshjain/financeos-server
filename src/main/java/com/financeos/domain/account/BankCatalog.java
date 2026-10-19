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
            new Bank("pnb", "Punjab National Bank", List.of("Punjab National Bank", "PNB")),
            new Bank("bank_of_baroda", "Bank of Baroda", List.of("Bank of Baroda")),
            new Bank("canara", "Canara Bank", List.of("Canara Bank")),
            new Bank("union_bank", "Union Bank of India", List.of("Union Bank of India", "Union Bank")),
            new Bank("bank_of_india", "Bank of India", List.of("Bank of India")),
            new Bank("indian_bank", "Indian Bank", List.of("Indian Bank")),
            new Bank("central_bank", "Central Bank of India", List.of("Central Bank of India")),
            new Bank("iob", "Indian Overseas Bank", List.of("Indian Overseas Bank", "IOB")),
            new Bank("uco", "UCO Bank", List.of("UCO Bank")),
            new Bank("bank_of_maharashtra", "Bank of Maharashtra", List.of("Bank of Maharashtra")),
            new Bank("punjab_sind", "Punjab & Sind Bank", List.of("Punjab & Sind Bank", "Punjab and Sind Bank")),
            new Bank("hdfc", "HDFC Bank", List.of("HDFC Bank", "HDFC")),
            new Bank("icici", "ICICI Bank", List.of("ICICI Bank", "ICICI")),
            new Bank("axis", "Axis Bank", List.of("Axis Bank")),
            new Bank("kotak", "Kotak Mahindra Bank", List.of("Kotak Mahindra Bank", "Kotak")),
            new Bank("indusind", "IndusInd Bank", List.of("IndusInd Bank", "IndusInd")),
            new Bank("yes", "Yes Bank", List.of("Yes Bank")),
            new Bank("idfc_first", "IDFC First Bank", List.of("IDFC First Bank", "IDFC FIRST", "IDFC")),
            new Bank("federal", "Federal Bank", List.of("Federal Bank")),
            new Bank("rbl", "RBL Bank", List.of("RBL Bank", "RBL")),
            new Bank("bandhan", "Bandhan Bank", List.of("Bandhan Bank")),
            new Bank("idbi", "IDBI Bank", List.of("IDBI Bank", "IDBI")),
            new Bank("csb", "CSB Bank", List.of("CSB Bank", "Catholic Syrian Bank")),
            new Bank("city_union", "City Union Bank", List.of("City Union Bank")),
            new Bank("dcb", "DCB Bank", List.of("DCB Bank")),
            new Bank("dhanlaxmi", "Dhanlaxmi Bank", List.of("Dhanlaxmi Bank")),
            new Bank("jk_bank", "J&K Bank", List.of("Jammu & Kashmir Bank", "Jammu and Kashmir Bank", "J&K Bank")),
            new Bank("karnataka", "Karnataka Bank", List.of("Karnataka Bank")),
            new Bank("karur_vysya", "Karur Vysya Bank", List.of("Karur Vysya Bank", "KVB")),
            new Bank("nainital", "Nainital Bank", List.of("Nainital Bank")),
            new Bank("south_indian", "South Indian Bank", List.of("South Indian Bank")),
            new Bank("tmb", "Tamilnad Mercantile Bank", List.of("Tamilnad Mercantile Bank", "TMB")),
            new Bank("au", "AU Small Finance Bank", List.of("AU Small Finance Bank", "AU Bank")),
            new Bank("equitas", "Equitas Small Finance Bank", List.of("Equitas Small Finance Bank", "Equitas")),
            new Bank("ujjivan", "Ujjivan Small Finance Bank", List.of("Ujjivan Small Finance Bank", "Ujjivan")),
            new Bank("jana", "Jana Small Finance Bank", List.of("Jana Small Finance Bank", "Jana Bank")),
            new Bank("suryoday", "Suryoday Small Finance Bank", List.of("Suryoday Small Finance Bank", "Suryoday")),
            new Bank("esaf", "ESAF Small Finance Bank", List.of("ESAF Small Finance Bank", "ESAF")),
            new Bank("utkarsh", "Utkarsh Small Finance Bank", List.of("Utkarsh Small Finance Bank", "Utkarsh")),
            new Bank("slice", "slice Small Finance Bank", List.of("slice Small Finance Bank", "North East Small Finance Bank", "slice")),
            new Bank("capital", "Capital Small Finance Bank", List.of("Capital Small Finance Bank")),
            new Bank("unity", "Unity Small Finance Bank", List.of("Unity Small Finance Bank", "Unity Bank")),
            new Bank("shivalik", "Shivalik Small Finance Bank", List.of("Shivalik Small Finance Bank", "Shivalik")),
            new Bank("airtel_payments", "Airtel Payments Bank", List.of("Airtel Payments Bank")),
            new Bank("ippb", "India Post Payments Bank", List.of("India Post Payments Bank", "IPPB")),
            new Bank("fino", "Fino Payments Bank", List.of("Fino Payments Bank", "Fino Bank")),
            new Bank("jio_payments", "Jio Payments Bank", List.of("Jio Payments Bank")),
            new Bank("paytm_payments", "Paytm Payments Bank", List.of("Paytm Payments Bank")),
            new Bank("nsdl_payments", "NSDL Payments Bank", List.of("NSDL Payments Bank")),
            new Bank("amex", "American Express", List.of("American Express", "Amex")),
            new Bank("standard_chartered", "Standard Chartered", List.of("Standard Chartered")),
            new Bank("hsbc", "HSBC", List.of("HSBC")),
            new Bank("dbs", "DBS Bank", List.of("DBS Bank", "digibank by DBS", "DBS")),
            new Bank("citi", "Citibank", List.of("Citibank", "Citi")),
            new Bank("deutsche", "Deutsche Bank", List.of("Deutsche Bank")),
            new Bank("sbm", "SBM Bank India", List.of("SBM Bank")),
            new Bank("bobcard", "BOBCARD", List.of("BOBCARD", "BOB Card", "BOB Financial"))
    );

    private static final Map<String, Bank> BY_ID = BANKS.stream()
            .collect(Collectors.toUnmodifiableMap(Bank::id, Function.identity()));

    private record AliasPattern(String bankId, int length, Pattern pattern) {}

    /** Every alias of every bank, longest first, so "South Indian Bank" is consumed before "Indian Bank" can match inside it. */
    private static final List<AliasPattern> ALIAS_PATTERNS = BANKS.stream()
            .flatMap(b -> b.aliases().stream().map(a -> new AliasPattern(b.id(), a.length(), aliasPattern(a))))
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
                found.add(ap.bankId());
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
