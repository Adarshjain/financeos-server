package com.financeos.gmail.accountdetect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fintech card/account brands (Jupiter, Fi) mail statements for accounts that sit at their partner bank
 * ({@link BankDirectory#PARTNERS}). A brand sender therefore "covers" an account at any of its partner banks.
 */
public final class BankPartners {

    /** Brands whose own sender domains route to partner-bank accounts. Others in PARTNERS (slice, kiwi, ...) stay exact-bank. */
    static final Set<String> MAIL_BRANDS = Set.of("fi", "jupiter");

    /** IFSC bank prefixes (the 4 letters before the 0) of the partner banks. */
    private static final Map<String, String> IFSC_BANK = Map.of("FDRL", "federal", "CSBK", "csb");
    private static final Pattern IFSC = Pattern.compile("\\b([A-Z]{4})0[A-Z0-9]{6}\\b");

    private BankPartners() {}

    /** The partner banks of a mail brand, empty for any other bank. */
    public static List<String> partnerBanks(String bank) {
        return isMailBrand(bank) ? BankDirectory.partnersOf(bank) : List.of();
    }

    public static boolean isMailBrand(String bank) {
        return bank != null && MAIL_BRANDS.contains(bank);
    }

    /**
     * The real bank behind a brand's statement: the partner bank whose IFSC prefix the text prints (exactly one such
     * bank), else the one partner bank the text names, else the brand's only partner (fi gives federal), else the brand
     * itself. A bank that is not a mail brand is returned as is.
     */
    public static String resolveBank(String bank, String text) {
        if (!isMailBrand(bank)) return bank;
        List<String> partners = BankDirectory.partnersOf(bank);
        String t = text == null ? "" : text;
        java.util.Set<String> byIfsc = new java.util.LinkedHashSet<>();
        Matcher m = IFSC.matcher(t);
        while (m.find()) {
            String b = IFSC_BANK.get(m.group(1));
            if (b != null && partners.contains(b)) byIfsc.add(b);
        }
        if (byIfsc.size() == 1) return byIfsc.iterator().next();
        if (byIfsc.isEmpty()) {
            java.util.Set<String> named = new java.util.LinkedHashSet<>();
            for (String b : BankDirectory.issuersIn(t.toLowerCase(java.util.Locale.ROOT))) if (partners.contains(b)) named.add(b);
            if (named.size() == 1) return named.iterator().next();
            if (named.isEmpty() && partners.size() == 1) return partners.get(0);
        }
        return bank;
    }

    /** True when the sender's bank is the account's bank, or a mail brand whose partner banks include the account's bank. */
    public static boolean covers(String accountBank, String senderBank) {
        if (accountBank == null || senderBank == null) {
            return false;
        }
        return accountBank.equals(senderBank)
                || (MAIL_BRANDS.contains(senderBank) && BankDirectory.partnersOf(senderBank).contains(accountBank));
    }

    /** Mail brands whose mail covers accounts at {@code bank} (federal gives jupiter and fi; csb gives jupiter), in directory order. */
    public static List<String> brandsFor(String bank) {
        List<String> out = new ArrayList<>();
        if (bank == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : BankDirectory.PARTNERS.entrySet()) {
            if (MAIL_BRANDS.contains(e.getKey()) && e.getValue().contains(bank)) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
