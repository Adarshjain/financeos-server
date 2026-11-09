package com.financeos.gmail.accountdetect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fintech card/account brands (Jupiter, Fi) mail statements for accounts that sit at their partner bank
 * ({@link BankDirectory#PARTNERS}). A brand sender therefore "covers" an account at any of its partner banks.
 */
public final class BankPartners {

    /** Brands whose own sender domains route to partner-bank accounts. Others in PARTNERS (slice, kiwi, ...) stay exact-bank. */
    static final Set<String> MAIL_BRANDS = Set.of("fi", "jupiter");

    private BankPartners() {}

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
