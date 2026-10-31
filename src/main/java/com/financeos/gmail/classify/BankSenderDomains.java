package com.financeos.gmail.classify;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a sender address domain to a {@code BankCatalog} id. Matches by suffix, so
 * {@code alerts.hdfcbank.net} resolves like {@code hdfcbank.net}. Domains of banks without a catalog
 * id (kotak) are intentionally absent.
 */
public final class BankSenderDomains {

    private static final Map<String, String> DOMAIN_TO_BANK = new LinkedHashMap<>();

    static {
        put("hdfc", "hdfcbank.net", "hdfcbank.com", "hdfcbank.bank.in", "hdfc.bank.in");
        put("icici", "icicibank.com", "icici.bank.in");
        put("axis", "axisbank.com", "axis.bank.in");
        put("sbi", "sbi.co.in", "sbi.bank.in", "sbicard.com");
        put("yes", "yesbank.in", "yes.bank.in");
        put("idfc_first", "idfcfirstbank.com", "idfcfirst.bank.in");
        put("federal", "federalbank.co.in", "federal.bank.in", "scapia.cards");
        put("au", "aubank.in", "au.bank.in");
        put("equitas", "equitasbank.com");
        put("indusind", "indusind.com", "indusind.bank.in");
        put("bank_of_baroda", "bankofbaroda.co.in", "bankofbaroda.bank.in", "bobcard.co.in", "bobfinancial.com");
        put("canara", "canarabank.com");
        put("indian_bank", "indianbank.co.in");
        put("csb", "csb.co.in");
        put("slice", "sliceit.com");
        put("unity", "theunitybank.com");
        put("amex", "americanexpress.com", "aexp.com");
        put("hsbc", "hsbc.co.in", "hsbc.bank.in");
        put("sbm", "sbmbank.co.in");
    }

    private BankSenderDomains() {}

    private static void put(String bankId, String... domains) {
        for (String d : domains) {
            DOMAIN_TO_BANK.put(d, bankId);
        }
    }

    /** The catalog bank id for the sender address (or a bare domain), or null when unknown. */
    public static String bankIdFor(String address) {
        if (address == null) {
            return null;
        }
        String domain = address.trim().toLowerCase(Locale.ROOT);
        int at = domain.lastIndexOf('@');
        if (at >= 0) {
            domain = domain.substring(at + 1);
        }
        while (domain.endsWith(".") || domain.endsWith(">")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> e : DOMAIN_TO_BANK.entrySet()) {
            String d = e.getKey();
            if (domain.equals(d) || domain.endsWith("." + d)) {
                return e.getValue();
            }
        }
        return null;
    }

    static Map<String, String> all() {
        return DOMAIN_TO_BANK;
    }
}
