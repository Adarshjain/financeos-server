package com.financeos.statement.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which bank or card issuer a statement is from, by the position and weight of the evidence: the issuer's name in the
 * masthead or title, its own domain, its IFSC prefix and customer-care lines count; a name in a payment instruction
 * ("pay via HDFC NetBanking", "cheque in favour of ...") or in boilerplate far down counts almost nothing. A result
 * needs a unique, dominant issuer; otherwise null (unknown). Scores stay in memory; only the bank id leaves.
 */
final class IssuerDetector {

    private IssuerDetector() {}

    private record Issuer(String id, List<String> names, List<String> domains, List<String> ifsc) {}

    private static final List<Issuer> ISSUERS = new ArrayList<>();
    /** A brand card issued through partner banks: its own name in the header beats a partner named beside it. */
    private static final Map<String, Set<String>> PARTNERS = new LinkedHashMap<>();

    private static void i(String id, List<String> names, List<String> domains, List<String> ifsc) {
        ISSUERS.add(new Issuer(id, names, domains, ifsc));
    }

    static {
        i("hdfc", List.of("hdfc bank", "hdfc"), List.of("hdfcbank.com", "hdfcbank.net", "hdfc.bank.in"), List.of("HDFC0"));
        i("icici", List.of("icici bank", "icici"), List.of("icicibank.com", "icici.bank.in"), List.of("ICIC0"));
        i("sbi", List.of("sbi card", "sbi cards and payment services", "sbi cards", "state bank of india", "sbi"),
                List.of("sbicard.com", "sbi.co.in", "sbi.bank.in", "onlinesbi.sbi", "onlinesbi.com"), List.of("SBIN0"));
        i("axis", List.of("axis bank", "axis"), List.of("axisbank.com", "axis.bank.in", "axisbank.co.in"), List.of("UTIB0"));
        i("citi", List.of("citibank", "citi bank", "citi"), List.of("citibank.co.in", "citi.com", "online.citibank.co.in"), List.of("CITI0"));
        i("kotak", List.of("kotak mahindra bank", "kotak mahindra", "kotak"), List.of("kotak.com", "kotak.bank.in"), List.of("KKBK0"));
        i("idfc_first", List.of("idfc first bank", "idfc first", "idfc bank", "idfc"), List.of("idfcfirstbank.com", "idfcfirst.bank.in"), List.of("IDFB0"));
        i("indusind", List.of("indusind bank", "indusind"), List.of("indusind.com", "indusind.bank.in"), List.of("INDB0"));
        i("au", List.of("au small finance bank", "au small finance", "au bank"), List.of("aubank.in", "au.bank.in"), List.of("AUBL0"));
        i("yes", List.of("yes bank"), List.of("yesbank.in", "yes.bank.in"), List.of("YESB0"));
        i("rbl", List.of("rbl bank", "rbl"), List.of("rblbank.com"), List.of("RATN0"));
        i("federal", List.of("federal bank", "scapia"), List.of("federalbank.co.in", "federal.bank.in", "scapia.cards", "scapia.cc"), List.of("FDRL0"));
        i("bank_of_baroda", List.of("bank of baroda", "bobcard", "bob financial"),
                List.of("bankofbaroda.in", "bankofbaroda.co.in", "bankofbaroda.bank.in", "bobcard.co.in", "bobfinancial.com"), List.of("BARB0"));
        i("amex", List.of("american express", "amex"), List.of("americanexpress.com", "americanexpress.co.in", "aexp.com"), List.of());
        i("hsbc", List.of("hsbc"), List.of("hsbc.co.in", "hsbc.bank.in"), List.of("HSBC0"));
        i("sc", List.of("standard chartered"), List.of("sc.com", "standardchartered.com"), List.of("SCBL0"));
        i("dbs", List.of("dbs bank", "dbs"), List.of("dbs.com", "dbs.bank.in"), List.of("DBSS0"));
        i("pnb", List.of("punjab national bank", "pnb"), List.of("pnbindia.in", "netpnb.com"), List.of("PUNB0"));
        i("canara", List.of("canara bank"), List.of("canarabank.com", "canarabank.bank.in"), List.of("CNRB0"));
        i("union_bank", List.of("union bank of india", "union bank"), List.of("unionbankofindia.co.in", "unionbankofindia.bank.in"), List.of("UBIN0"));
        i("boi", List.of("bank of india"), List.of("bankofindia.co.in", "bankofindia.bank.in"), List.of("BKID0"));
        i("idbi", List.of("idbi bank", "idbi"), List.of("idbibank.in", "idbibank.com"), List.of("IBKL0"));
        i("bandhan", List.of("bandhan bank"), List.of("bandhanbank.com"), List.of("BDBL0"));
        i("sbm", List.of("sbm bank", "sbm"), List.of("sbmbank.co.in", "sbmbank.com"), List.of("STCB0"));
        i("onecard", List.of("onecard", "one card"), List.of("getonecard.app", "onecard.app"), List.of());
        i("jupiter", List.of("jupiter money", "jupiter"), List.of("jupiter.money"), List.of());
        i("fi", List.of("fi money", "epifi"), List.of("fi.money", "epifi.com"), List.of());
        i("equitas", List.of("equitas small finance bank", "equitas bank", "equitas"), List.of("equitasbank.com", "equitas.bank.in"), List.of("ESFB0"));
        i("ujjivan", List.of("ujjivan small finance bank", "ujjivan"), List.of("ujjivansfb.in"), List.of("UJVN0"));
        i("utkarsh", List.of("utkarsh small finance bank", "utkarsh"), List.of("utkarsh.bank"), List.of("UTKS0"));
        i("suryoday", List.of("suryoday small finance bank", "suryoday"), List.of("suryodaybank.com"), List.of("SURY0"));
        i("karnataka", List.of("karnataka bank"), List.of("karnatakabank.com"), List.of("KARB0"));
        i("kvb", List.of("karur vysya bank", "kvb"), List.of("kvb.co.in"), List.of("KVBL0"));
        i("cub", List.of("city union bank"), List.of("cityunionbank.com"), List.of("CIUB0"));
        i("south_indian", List.of("south indian bank"), List.of("southindianbank.com"), List.of("SIBL0"));
        i("dcb", List.of("dcb bank"), List.of("dcbbank.com"), List.of("DCBL0"));
        i("airtel_payments", List.of("airtel payments bank"), List.of("airtelbank.com"), List.of("AIRP0"));
        i("paytm_payments", List.of("paytm payments bank"), List.of("paytmbank.com"), List.of("PYTM0"));
        i("indian_bank", List.of("indian bank"), List.of("indianbank.in", "indianbank.co.in"), List.of("IDIB0"));
        i("csb", List.of("csb bank"), List.of("csb.co.in"), List.of("CSBK0"));
        i("unity", List.of("unity small finance bank"), List.of("theunitybank.com"), List.of("UNBA0"));
        i("slice", List.of("slice"), List.of("sliceit.com"), List.of());

        PARTNERS.put("onecard", Set.of("federal", "sbm", "idfc_first", "bank_of_baroda", "csb", "yes", "indusind"));
        PARTNERS.put("jupiter", Set.of("federal", "csb"));
        PARTNERS.put("fi", Set.of("federal"));
        PARTNERS.put("slice", Set.of("unity", "sbm"));
    }

    /** A name inside a payment instruction or a regulator mention is not the issuer. */
    private static final Pattern NOT_THE_ISSUER = Pattern.compile(
            "\\b(?:net ?banking|neft|rtgs|imps|upi|cheque|chq|demand draft|payable (?:to|at)|in favou?r of|pay(?:ment)? (?:via|through|using|by|at|options?|modes?)|"
                    + "bill ?desk|autopay|auto debit|nach|ecs|mandate|branch(?:es)? of|any (?:hdfc|icici|axis|sbi) |reserve bank|rbi)\\b");

    /** "State Bank of India", "City Union Bank", "South Indian Bank" contain another issuer's name: it does not count there. */
    private static final Map<String, String[]> EXCLUDE_BEFORE = Map.of(
            "boi", new String[]{"state", "union", "reserve"}, "union_bank", new String[]{"city"}, "indian_bank", new String[]{"south"});

    private static final Pattern TITLE = Pattern.compile("(?:credit card|card|account|e-?) ?statement|statement of account");
    private static final Pattern IFSC = Pattern.compile("\\b([A-Z]{4})0[A-Z0-9]{6}\\b");

    /** Bank id, or null when there is no unique dominant issuer. {@code flags} gets a shape-only note. */
    static String detect(List<String> lines, Set<String> flags) {
        Map<String, Integer> headLines = new LinkedHashMap<>();
        Set<String> titled = new TreeSet<>();
        Map<String, Double> head = new LinkedHashMap<>(), mid = new LinkedHashMap<>(), tail = new LinkedHashMap<>(), ifsc = new LinkedHashMap<>();
        for (int idx = 0; idx < lines.size(); idx++) {
            String raw = lines.get(idx);
            String lower = raw.toLowerCase(Locale.ROOT);
            // a row of a beneficiary / payment-channel table (it carries an IFSC) is a payment instruction, unless it is in the masthead
            boolean payment = NOT_THE_ISSUER.matcher(lower).find() || (idx >= 15 && IFSC.matcher(raw).find());
            double zone = idx < 6 ? 6 : idx < 25 ? 4 : idx < 60 ? 1.5 : 0.4;
            String padded = " " + lower.replaceAll("[^a-z0-9]+", " ") + " ";
            for (Issuer is : ISSUERS) {
                double add = 0;
                if (!payment) {
                    String rest = padded;
                    for (String n : is.names) {
                        String needle = " " + n + " ";
                        int at = rest.indexOf(needle);
                        boolean hit = false;
                        while (at >= 0) {
                            if (!precededByAny(rest, at, EXCLUDE_BEFORE.getOrDefault(is.id, new String[0]))) hit = true;
                            rest = rest.substring(0, at) + " " + "_".repeat(needle.length() - 2) + " " + rest.substring(at + needle.length());
                            at = rest.indexOf(needle, at + 1);
                        }
                        if (hit) { add = zone; break; }
                    }
                    for (String d : is.domains) if (lower.contains(d) && !lower.contains("@" + d)) { add = Math.max(add, zone >= 4 ? 4 : 2.5); }
                    if (lower.contains("@") && is.domains.stream().anyMatch(lower::contains)) add = Math.max(add, 1); // an email: weak
                }
                double bonus = add > 0 && TITLE.matcher(lower).find() ? 4 : 0;   // the issuer named in the document's own title line
                if (add > 0 && idx < 25) {
                    // the first lines naming an issuer count in full, repeats (a table of payment channels) count less
                    int seenLines = headLines.merge(is.id, 1, Integer::sum);
                    add *= seenLines == 1 ? 1 : seenLines == 2 ? 0.5 : 0.25;
                    if (bonus > 0 && idx < 6) titled.add(is.id);
                }
                if (add > 0) (idx < 25 ? head : idx < 60 ? mid : tail).merge(is.id, Math.min(add, zone) + bonus, Double::sum);
            }
            if (idx < 15 && !payment) {
                Matcher m = IFSC.matcher(raw);
                Set<String> seen = new TreeSet<>();
                while (m.find()) {
                    String pre = m.group(1) + "0";
                    if (!seen.add(pre)) continue;
                    for (Issuer is : ISSUERS) if (is.ifsc.contains(pre)) ifsc.merge(is.id, 8.0, Double::sum);
                }
            }
        }
        // the issuer named in the document's own title, near the top, and nobody else titled: the title decides
        if (titled.size() == 1) head.merge(titled.iterator().next(), 6.0, Double::sum);
        // a long boilerplate tail must never outvote a header: the middle counts up to 6, the tail up to 3
        Map<String, Double> score = new LinkedHashMap<>(head);
        mid.forEach((k, v) -> score.merge(k, Math.min(v, 6.0), Double::sum));
        tail.forEach((k, v) -> score.merge(k, Math.min(v, 3.0), Double::sum));
        ifsc.forEach((k, v) -> score.merge(k, Math.min(v, 8.0), Double::sum));
        // "ex-Citi": the card is Axis's now; "citi" counts only when Axis shows nothing of its own
        if (score.containsKey("citi") && score.getOrDefault("axis", 0.0) >= 4) { score.merge("axis", score.remove("citi") / 2, Double::sum); }
        // partner brands
        for (var e : PARTNERS.entrySet()) {
            if (head.getOrDefault(e.getKey(), 0.0) >= 4) for (String p : e.getValue()) score.remove(p);
        }
        String best = null;
        double top = 0, second = 0;
        for (var e : score.entrySet()) {
            double v = e.getValue();
            if (v > top) { second = top; top = v; best = e.getKey(); }
            else if (v > second) second = v;
        }
        if (best == null) { flags.add("bank:none"); return null; }
        if (top < 4 || (second > 0 && (top < 2 * second || top - second < 3))) {
            StringBuilder sb = new StringBuilder(top < 4 ? "bank:weak(" : "bank:ambiguous(");
            score.entrySet().stream().filter(e -> e.getValue() >= 2).forEach(e -> sb.append(e.getKey()).append('=').append(Math.round(e.getValue())).append(' '));
            flags.add(sb.toString().trim() + ")");
            return null;
        }
        return best;
    }

    private static boolean precededByAny(String padded, int at, String... words) {
        String before = padded.substring(0, at).trim();
        for (String w : words) if (before.endsWith(" " + w) || before.equals(w)) return true;
        return false;
    }

    static List<String> ids() {
        return ISSUERS.stream().map(Issuer::id).toList();
    }
}
