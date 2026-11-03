package com.financeos.gmail.accountdetect;

import com.financeos.gmail.classify.BankSenderDomains;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bank-generic dictionaries: sender domain to bank id (private, public sector, small finance, payments and foreign
 * banks, fintech card programmes), payment apps and wallets, and issuer names (for payment-app mail and statement
 * headers). Card products live in {@link ProductCatalog}. Nothing here is specific to one user.
 */
public final class BankDirectory {

    /** Sender domains outside the catalog that still belong to a bank or card issuer. */
    static final Map<String, String> EXTRA_BANK_DOMAINS = new LinkedHashMap<>();
    /** Payment apps: an independent channel that can confirm but never create an entity. */
    static final Map<String, String> APP_DOMAINS = new LinkedHashMap<>();
    static final Map<String, List<String>> ISSUER_NAMES = new LinkedHashMap<>();

    /** Payments banks: accounts and debit cards only; they can not issue credit cards. */
    static final Set<String> PAYMENTS_BANKS = Set.of("airtel_payments", "paytm_payments", "ippb", "fino", "jio_payments", "nsdl_payments");
    /** Fintech card programmes and the banks that issue their cards (data only: the programme stays the entity's bank). */
    static final Map<String, List<String>> PARTNERS = new LinkedHashMap<>();

    static {
        // private and foreign banks outside the catalog
        extra("kotak", "kotak.com", "kotak.bank.in");
        extra("rbl", "rblbank.com", "rbl.bank.in");
        extra("dbs", "dbs.com", "dbs.bank.in");
        extra("sc", "sc.com");
        extra("citi", "citi.com", "citibank.co.in", "citibank.com");
        extra("deutsche", "db.com", "deutschebank.co.in");
        extra("barclays", "barclays.in");
        extra("idbi", "idbibank.com", "idbibank.co.in", "idbi.bank.in", "idbi.co.in");
        extra("south_indian", "southindianbank.com", "southindianbank.bank.in", "sib.co.in");
        extra("karnataka", "karnatakabank.com", "karnatakabank.co.in", "ktkbank.com", "karnatakabank.bank.in");
        extra("kvb", "kvb.co.in", "kvb.bank.in");
        extra("cub", "cityunionbank.com", "cityunionbank.in", "cub.bank.in");
        extra("tmb", "tmb.in", "tmb.bank.in");
        extra("dhanlaxmi", "dhanbank.com", "dhanlaxmibank.co.in");
        extra("jk", "jkbank.com", "jkbank.net", "jkbank.bank.in");
        extra("bandhan", "bandhanbank.com", "bandhan.bank.in", "bandhanbank.co.in");
        extra("dcb", "dcbbank.com", "dcb.bank.in");
        extra("csb", "csb.bank.in");
        extra("equitas", "equitas.bank.in");
        extra("canara", "canarabank.in", "canarabank.bank.in", "canarabank.co.in");
        extra("indian_bank", "indianbank.in", "indianbank.bank.in");
        // public sector banks
        extra("pnb", "pnb.co.in", "pnb.bank.in", "pnbindia.in");
        extra("union_bank", "unionbankofindia.co.in", "unionbankofindia.bank.in", "unionbankofindia.com");
        extra("boi", "bankofindia.co.in", "boi.bank.in", "bankofindia.com");
        extra("central", "centralbank.co.in", "centralbank.bank.in", "centralbankofindia.co.in");
        extra("iob", "iob.in", "iob.bank.in", "iob.co.in");
        extra("uco", "ucobank.com", "ucobank.co.in", "uco.bank.in");
        extra("bom", "mahabank.co.in", "bankofmaharashtra.in", "mahabank.bank.in");
        extra("psb", "psbindia.com", "punjabandsindbank.co.in");
        // small finance banks
        extra("ujjivan", "ujjivansfb.in", "ujjivan.bank.in", "ujjivan.com");
        extra("suryoday", "suryodaybank.com", "suryoday.bank.in");
        extra("esaf", "esafbank.com", "esaf.bank.in");
        extra("jana", "janabank.com", "jana.bank.in");
        extra("utkarsh", "utkarsh.bank", "utkarsh.bank.in");
        extra("shivalik", "shivalikbank.com");
        extra("capital_sfb", "capitalbank.co.in");
        extra("nesfb", "nesfb.com");
        extra("au", "fincarebank.com");   // Fincare merged into AU
        // payments banks
        extra("airtel_payments", "airtelbank.com");
        extra("paytm_payments", "paytmbank.com");
        extra("ippb", "ippbonline.com", "ippbonline.in");
        extra("fino", "finobank.com");
        extra("jio_payments", "jiopaymentsbank.com");
        extra("nsdl_payments", "nsdlbank.com");
        // fintech card programmes (issued by a partner bank)
        extra("onecard", "onecard.app", "getonecard.app");
        extra("jupiter", "jupiter.money");
        extra("fi", "fi.money");
        extra("uni", "uni.club");
        extra("federal", "scapia.cc");   // Scapia cards are issued by Federal Bank
        PARTNERS.put("onecard", List.of("federal", "bank_of_baroda", "sbm", "south_indian", "csb", "indian_bank"));
        PARTNERS.put("jupiter", List.of("federal", "csb"));
        PARTNERS.put("fi", List.of("federal"));
        PARTNERS.put("kiwi", List.of("yes", "axis"));
        PARTNERS.put("niyo", List.of("sbm", "equitas", "dcb"));
        PARTNERS.put("slice", List.of("sbm", "nesfb"));
        PARTNERS.put("uni", List.of("sbm", "yes"));

        // payment apps and wallets: an independent channel that can confirm but never create an entity
        APP_DOMAINS.put("cred.club", "app:cred");
        APP_DOMAINS.put("paytm.com", "app:paytm");
        APP_DOMAINS.put("amazonpay.in", "app:amazonpay");
        APP_DOMAINS.put("phonepe.com", "app:phonepe");
        APP_DOMAINS.put("mobikwik.com", "app:mobikwik");
        APP_DOMAINS.put("freecharge.in", "app:freecharge");
        APP_DOMAINS.put("super.money", "app:supermoney");
        // card programmes whose cards a partner bank issues and bills (Kiwi, Niyo): their own mail only confirms
        APP_DOMAINS.put("gokiwi.in", "app:kiwi");
        APP_DOMAINS.put("goniyo.com", "app:niyo");

        ISSUER_NAMES.put("hdfc", List.of("hdfc bank", "hdfc"));
        ISSUER_NAMES.put("icici", List.of("icici bank", "icici"));
        ISSUER_NAMES.put("axis", List.of("axis bank", "axis"));
        ISSUER_NAMES.put("sbi", List.of("state bank of india", "sbi card", "sbi"));
        ISSUER_NAMES.put("yes", List.of("yes bank"));
        ISSUER_NAMES.put("idfc_first", List.of("idfc first bank", "idfc first", "idfc bank"));
        ISSUER_NAMES.put("federal", List.of("federal bank"));
        ISSUER_NAMES.put("au", List.of("au small finance bank", "au bank"));
        ISSUER_NAMES.put("equitas", List.of("equitas small finance bank", "equitas"));
        ISSUER_NAMES.put("indusind", List.of("indusind bank", "indusind"));
        ISSUER_NAMES.put("bank_of_baroda", List.of("bank of baroda", "bobcard"));
        ISSUER_NAMES.put("canara", List.of("canara bank"));
        ISSUER_NAMES.put("indian_bank", List.of("indian bank"));
        ISSUER_NAMES.put("csb", List.of("csb bank"));
        ISSUER_NAMES.put("slice", List.of("slice"));
        ISSUER_NAMES.put("unity", List.of("unity small finance bank", "unity bank"));
        ISSUER_NAMES.put("amex", List.of("american express", "amex"));
        ISSUER_NAMES.put("hsbc", List.of("hsbc"));
        ISSUER_NAMES.put("sbm", List.of("sbm bank"));
        ISSUER_NAMES.put("kotak", List.of("kotak mahindra bank", "kotak"));
        ISSUER_NAMES.put("rbl", List.of("rbl bank"));
        ISSUER_NAMES.put("dbs", List.of("dbs bank"));
        ISSUER_NAMES.put("sc", List.of("standard chartered bank", "standard chartered"));
        ISSUER_NAMES.put("citi", List.of("citibank", "citi bank"));
        ISSUER_NAMES.put("deutsche", List.of("deutsche bank"));
        ISSUER_NAMES.put("barclays", List.of("barclays bank", "barclays"));
        ISSUER_NAMES.put("idbi", List.of("idbi bank", "idbi"));
        ISSUER_NAMES.put("south_indian", List.of("south indian bank"));
        ISSUER_NAMES.put("karnataka", List.of("karnataka bank"));
        ISSUER_NAMES.put("kvb", List.of("karur vysya bank"));
        ISSUER_NAMES.put("cub", List.of("city union bank"));
        ISSUER_NAMES.put("tmb", List.of("tamilnad mercantile bank"));
        ISSUER_NAMES.put("dhanlaxmi", List.of("dhanlaxmi bank"));
        ISSUER_NAMES.put("jk", List.of("jammu and kashmir bank", "jammu kashmir bank", "j k bank"));
        ISSUER_NAMES.put("bandhan", List.of("bandhan bank"));
        ISSUER_NAMES.put("dcb", List.of("dcb bank"));
        ISSUER_NAMES.put("pnb", List.of("punjab national bank", "pnb"));
        ISSUER_NAMES.put("union_bank", List.of("union bank of india", "union bank"));
        ISSUER_NAMES.put("boi", List.of("bank of india"));
        ISSUER_NAMES.put("central", List.of("central bank of india"));
        ISSUER_NAMES.put("iob", List.of("indian overseas bank"));
        ISSUER_NAMES.put("uco", List.of("uco bank"));
        ISSUER_NAMES.put("bom", List.of("bank of maharashtra"));
        ISSUER_NAMES.put("psb", List.of("punjab and sind bank", "punjab sind bank"));
        ISSUER_NAMES.put("ujjivan", List.of("ujjivan small finance bank", "ujjivan"));
        ISSUER_NAMES.put("suryoday", List.of("suryoday small finance bank", "suryoday"));
        ISSUER_NAMES.put("esaf", List.of("esaf small finance bank", "esaf"));
        ISSUER_NAMES.put("jana", List.of("jana small finance bank"));
        ISSUER_NAMES.put("utkarsh", List.of("utkarsh small finance bank"));
        ISSUER_NAMES.put("shivalik", List.of("shivalik small finance bank"));
        ISSUER_NAMES.put("capital_sfb", List.of("capital small finance bank"));
        ISSUER_NAMES.put("nesfb", List.of("north east small finance bank"));
        ISSUER_NAMES.put("airtel_payments", List.of("airtel payments bank"));
        ISSUER_NAMES.put("paytm_payments", List.of("paytm payments bank"));
        ISSUER_NAMES.put("ippb", List.of("india post payments bank", "ippb"));
        ISSUER_NAMES.put("fino", List.of("fino payments bank"));
        ISSUER_NAMES.put("jio_payments", List.of("jio payments bank"));
        ISSUER_NAMES.put("nsdl_payments", List.of("nsdl payments bank"));
    }

    private static void extra(String bank, String... domains) {
        for (String d : domains) EXTRA_BANK_DOMAINS.put(d, bank);
    }

    private BankDirectory() {}

    /** Bank id for a sender domain, an app id ("app:cred") for a payment app, or null. */
    public static String bankForDomain(String domain) {
        if (domain == null || domain.isBlank()) return null;
        String d = domain.toLowerCase(Locale.ROOT);
        for (var e : APP_DOMAINS.entrySet()) if (d.equals(e.getKey()) || d.endsWith("." + e.getKey())) return e.getValue();
        String id = BankSenderDomains.bankIdFor(d);
        if (id != null) return id;
        for (var e : EXTRA_BANK_DOMAINS.entrySet()) if (d.equals(e.getKey()) || d.endsWith("." + e.getKey())) return e.getValue();
        return null;
    }

    public static boolean isApp(String bank) {
        return bank != null && bank.startsWith("app:");
    }

    /** Every sender domain worth listing (catalog + extras + apps). */
    public static List<String> allDomains() {
        List<String> all = new ArrayList<>(BankSenderDomains.domains());
        all.addAll(EXTRA_BANK_DOMAINS.keySet());
        all.addAll(APP_DOMAINS.keySet());
        return all;
    }

    /** Lower-case word tokens that name this bank in running text (e.g. hdfc, bank). */
    static Set<String> bankWords(String bank) {
        Set<String> out = new java.util.HashSet<>(Set.of("bank", "card", "the", "your"));
        for (String n : ISSUER_NAMES.getOrDefault(bank, List.of())) out.addAll(List.of(n.split(" ")));
        return out;
    }

    static List<String> issuerNames(String bank) {
        return ISSUER_NAMES.getOrDefault(bank, List.of());
    }

    /** Every (name, bank) pair, longest name first, so "state bank of india" is never also read as "bank of india". */
    private static final List<Map.Entry<String, String>> NAMES_LONGEST_FIRST = new ArrayList<>();

    static {
        for (var e : ISSUER_NAMES.entrySet()) for (String n : e.getValue()) NAMES_LONGEST_FIRST.add(Map.entry(n, e.getKey()));
        NAMES_LONGEST_FIRST.sort((x, y) -> y.getKey().length() - x.getKey().length());
    }

    /** Issuer mentions in a lower-case text: bank -> {count, first position}, each name span counted once. */
    private static Map<String, int[]> scanIssuers(String lower) {
        StringBuilder rest = new StringBuilder(" " + lower.replaceAll("[^a-z0-9]+", " ") + " ");
        Map<String, int[]> found = new LinkedHashMap<>();
        for (var e : NAMES_LONGEST_FIRST) {
            String needle = " " + e.getKey() + " ";
            int i = rest.indexOf(needle);
            while (i >= 0) {
                int[] c = found.computeIfAbsent(e.getValue(), k -> new int[]{0, Integer.MAX_VALUE});
                c[0]++;
                c[1] = Math.min(c[1], i);
                for (int k = i + 1; k < i + needle.length() - 1; k++) rest.setCharAt(k, '_');
                i = rest.indexOf(needle, i + 1);
            }
        }
        return found;
    }

    /**
     * The issuer a document is from: the issuer named earliest must also be named strictly more often than any other;
     * otherwise null (unknown), so a statement that mentions a partner bank is never assigned to it.
     */
    public static String dominantIssuer(String lower) {
        Map<String, int[]> counts = scanIssuers(lower);
        String first = null;
        int firstPos = Integer.MAX_VALUE;
        for (var c : counts.entrySet()) {
            if (c.getValue()[1] < firstPos) {
                firstPos = c.getValue()[1];
                first = c.getKey();
            }
        }
        if (first == null) return null;
        int top = counts.get(first)[0];
        for (var c : counts.entrySet()) if (!c.getKey().equals(first) && c.getValue()[0] >= top) return null;
        return first;
    }

    /** Canonical product names of this issuer (from the product catalogue). */
    static List<String> variantsOf(String bank) {
        return List.copyOf(new java.util.LinkedHashSet<>(ProductCatalog.needles(bank).values()));
    }

    /** A payments bank: it can not issue credit cards. */
    public static boolean isPaymentsBank(String bank) {
        return bank != null && PAYMENTS_BANKS.contains(bank);
    }

    static List<String> partnersOf(String program) {
        return PARTNERS.getOrDefault(program, List.of());
    }

    /** Banks named in a lower-case text (longest names first, each span counted once), in order of first appearance. */
    public static List<String> issuersIn(String lower) {
        List<Map.Entry<String, int[]>> l = new ArrayList<>(scanIssuers(lower).entrySet());
        l.sort((x, y) -> x.getValue()[1] - y.getValue()[1]);
        List<String> out = new ArrayList<>();
        for (var e : l) out.add(e.getKey());
        return out;
    }
}
