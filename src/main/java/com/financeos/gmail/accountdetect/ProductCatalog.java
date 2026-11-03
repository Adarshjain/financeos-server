package com.financeos.gmail.accountdetect;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Card products per issuer, loaded from {@code accountdetect/products.yml}: canonical names (short marketed name, no
 * issuer words, no "Credit Card") with their aliases, current and legacy. Turns any product phrase into its
 * canonical name, or null when the catalogue does not know it.
 */
public final class ProductCatalog {

    /** Words that only name a tier; a name made only of them does not identify a product (reported LOW at most). */
    static final Set<String> TIER_WORDS = Set.of("platinum", "gold", "silver", "titanium", "classic", "signature", "select",
            "premier", "premium", "prime", "elite", "privilege", "reserve", "wealth", "preferred", "infinite", "black", "private",
            "exclusive", "business", "corporate");
    /** Network and finish words a phrase may carry after the name ("Coral RuPay", "Infinia Metal Edition"). */
    static final Set<String> TRAILING_WORDS = Set.of("visa", "mastercard", "rupay", "amex", "diners", "metal", "edition", "contactless", "chip",
            "international", "card", "credit");
    /** Single-word names that are also ordinary words: they count in text only right next to the issuer's name. */
    static final Set<String> COMMON_WORDS = Set.of("edge", "neo", "white", "times", "ace", "play", "icon", "max", "smart", "wave",
            "pulse", "miles", "freedom", "easy", "aura", "duo", "power", "wow", "spark", "ultimate", "horizon", "earn", "cashback",
            "solitaire", "royale", "delight", "legend", "tiger", "cookies", "jewel", "crest", "indulge", "spar", "titan", "tata",
            "apollo", "reliance", "yatra", "uni", "bharat", "empower", "yoddha", "gaj", "klick", "spont", "nomo", "swyp", "mine",
            "fibe", "kiwi", "atlas", "magnus", "select", "reserve", "premier", "prime", "elite");

    /** issuer -> normalised lookup key -> canonical name. */
    private static final Map<String, Map<String, String>> LOOKUP = new LinkedHashMap<>();
    /** issuer -> text needle (normalised alias as written) -> canonical name, longest needle first. */
    private static final Map<String, Map<String, String>> NEEDLES = new LinkedHashMap<>();
    /** issuer -> lookup keys of former names (renamedFrom / renamedTo): they resolve to the current name. */
    private static final Map<String, Set<String>> FORMER = new LinkedHashMap<>();
    /** issuer -> canonical names that are tier words but real products of that issuer only (Amex "Gold"). */
    private static final Map<String, Set<String>> TIER_PRODUCTS = new LinkedHashMap<>();

    static {
        load();
    }

    private ProductCatalog() {}

    @SuppressWarnings("unchecked")
    private static void load() {
        try (InputStream in = ProductCatalog.class.getResourceAsStream("/accountdetect/products.yml")) {
            if (in == null) throw new IllegalStateException("accountdetect/products.yml missing");
            Map<Object, Object> root = new Yaml().load(in);
            for (var issuer : root.entrySet()) {
                // an unquoted "yes" would load as a boolean: issuer ids must be strings
                if (!(issuer.getKey() instanceof String bank)) throw new IllegalStateException("issuer id is not a string: " + issuer.getKey());
                Map<Object, Object> products = (Map<Object, Object>) issuer.getValue();
                Map<String, String> lookup = new LinkedHashMap<>();
                List<Map.Entry<String, String>> needles = new ArrayList<>();
                Set<String> former = new java.util.HashSet<>();
                Set<String> tierProducts = new java.util.HashSet<>();
                // an entry is "Name: [aliases]" or "Name: {aliases: [..], renamedFrom: [..], renamedTo: Current,
                // tierProduct: true}"; a renamed product's old and new names are one product, reported by its current name
                Map<String, String> renamedTo = new LinkedHashMap<>();
                for (var p : products.entrySet()) {
                    if (p.getValue() instanceof Map<?, ?> m && m.get("renamedTo") != null) renamedTo.put(String.valueOf(p.getKey()), String.valueOf(m.get("renamedTo")));
                }
                for (var p : products.entrySet()) {
                    String declared = String.valueOf(p.getKey());
                    String canonical = declared;
                    for (int hop = 0; renamedTo.containsKey(canonical); hop++) {
                        if (hop > 5) throw new IllegalStateException("rename cycle: " + bank + " " + declared);
                        canonical = renamedTo.get(canonical);
                    }
                    if (!canonical.equals(declared) && !products.containsKey(canonical))
                        throw new IllegalStateException("renamedTo names no entry: " + bank + " " + declared);
                    List<String> names = new ArrayList<>();
                    List<String> old = new ArrayList<>();
                    names.add(declared);
                    if (!canonical.equals(declared)) old.add(declared);
                    if (p.getValue() instanceof Collection<?> c) for (Object a : c) names.add(String.valueOf(a));
                    else if (p.getValue() instanceof Map<?, ?> m) {
                        if (m.get("aliases") instanceof Collection<?> c) for (Object a : c) names.add(String.valueOf(a));
                        if (m.get("renamedFrom") instanceof Collection<?> c) for (Object a : c) { names.add(String.valueOf(a)); old.add(String.valueOf(a)); }
                        if (Boolean.TRUE.equals(m.get("tierProduct"))) tierProducts.add(canonical);
                    } else if (p.getValue() != null) throw new IllegalStateException("bad catalogue entry: " + bank + " " + declared);
                    if (!canonical.equals(declared)) old.addAll(names.subList(1, names.size()));
                    for (String o : old) former.add(key(bank, o));
                    for (String n : names) {
                        String key = key(bank, n);
                        if (key.isEmpty()) throw new IllegalStateException("product name is only issuer words: " + bank + " " + n);
                        String prev = lookup.putIfAbsent(key, canonical);
                        if (prev != null && !prev.equals(canonical))
                            throw new IllegalStateException("product alias names two products: " + bank + " " + n);
                        needles.add(Map.entry(norm(n), canonical));
                    }
                }
                FORMER.put(bank, former);
                TIER_PRODUCTS.put(bank, tierProducts);
                needles.sort((a, b) -> b.getKey().length() - a.getKey().length());
                Map<String, String> nm = new LinkedHashMap<>();
                for (var e : needles) nm.putIfAbsent(e.getKey(), e.getValue());
                LOOKUP.put(bank, lookup);
                NEEDLES.put(bank, nm);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Lower case, punctuation to single spaces ("+" kept: "Live+", "Elite+"). */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9+]+", " ").trim();
    }

    /** Lookup key: the normalised phrase without issuer words, "bank", "credit", "card", "the", "your". */
    static String key(String bank, String phrase) {
        Set<String> bw = BankDirectory.bankWords(bank);
        StringBuilder sb = new StringBuilder();
        for (String t : norm(phrase).split(" ")) {
            if (t.isEmpty() || bw.contains(t) || t.equals("credit") || t.equals("card")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * Canonical name of a product phrase for this issuer ("Swiggy HDFC Bank Credit Card" -> "Swiggy"), or null when the
     * catalogue does not know it. A trailing network or finish word is tried off ("Coral RuPay" -> "Coral").
     */
    public static String canonical(String bank, String phrase) {
        Map<String, String> lookup = LOOKUP.get(bank);
        if (lookup == null || phrase == null) return null;
        String k = key(bank, phrase);
        String hit = lookup.get(k);
        if (hit != null) return hit;
        List<String> toks = new ArrayList<>(List.of(k.split(" ")));
        while (toks.size() > 1 && TRAILING_WORDS.contains(toks.get(toks.size() - 1))) {
            toks.remove(toks.size() - 1);
            hit = lookup.get(String.join(" ", toks));
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * The name to report for a product phrase: its canonical name, else the phrase as printed without issuer words,
     * "Bank", "Credit" and "Card" (an uncatalogued name, which the contract keeps LOW); null when nothing is left.
     */
    public static String name(String bank, String phrase) {
        if (phrase == null || phrase.isBlank()) return null;
        String c = canonical(bank, phrase);
        if (c != null) return c;
        Set<String> bw = BankDirectory.bankWords(bank);
        StringBuilder sb = new StringBuilder();
        for (String t : phrase.trim().split("[\\s,;:|()]+")) {
            String l = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+]", "");
            if (l.isEmpty() || bw.contains(l) || l.equals("credit") || l.equals("card")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** A variant safe to print in reports: catalogue names as they are, anything else as "(uncatalogued)". */
    public static String printable(String bank, String name) {
        if (name == null) return null;
        return isCatalogued(bank, name) ? name : "(uncatalogued)";
    }

    /** True when the name is a canonical catalogue name of this issuer. */
    public static boolean isCatalogued(String bank, String name) {
        return name != null && name.equals(canonical(bank, name));
    }

    /** A name made only of tier words ("Platinum", "Visa Signature"): never a product identity on its own. */
    public static boolean tierOnly(String name) {
        String n = norm(name);
        if (n.isEmpty()) return true;
        for (String t : n.split(" ")) if (!TIER_WORDS.contains(t)) return false;
        return true;
    }

    /** A tier-word name that this issuer markets as a product of its own (Amex Gold, Amex Platinum). */
    public static boolean tierProduct(String bank, String canonical) {
        return canonical != null && TIER_PRODUCTS.getOrDefault(bank, Set.of()).contains(canonical);
    }

    /** True when the phrase is a former name of a renamed product (it resolves to the current name). */
    public static boolean formerName(String bank, String phrase) {
        return phrase != null && FORMER.getOrDefault(bank, Set.of()).contains(key(bank, phrase));
    }

    /** Two phrases that name one product (aliases, or old and new names of a renamed product). */
    public static boolean sameProduct(String bank, String a, String b) {
        String ca = canonical(bank, a);
        return ca != null && ca.equals(canonical(bank, b));
    }

    /**
     * Other current products of this issuer whose name strictly contains every word of this one ("Regalia" ->
     * "Regalia Gold"): a phrase naming only the shorter name may be a cut-off longer one.
     */
    public static List<String> longerNames(String bank, String canonical) {
        List<String> out = new ArrayList<>();
        if (canonical == null || !LOOKUP.containsKey(bank)) return out;
        Set<String> mine = Set.of(key(bank, canonical).split(" "));
        for (String other : new java.util.LinkedHashSet<>(LOOKUP.get(bank).values())) {
            if (other.equals(canonical)) continue;
            Set<String> theirs = new java.util.HashSet<>(List.of(key(bank, other).split(" ")));
            if (theirs.size() > mine.size() && theirs.containsAll(mine)) out.add(other);
        }
        return out;
    }

    /**
     * Other current products of this issuer whose name contains this one as text, or is contained in it, without
     * word nesting ("Zenith" / "Zenith+", "Smart" / "DigiSmart"): a glued suffix the text may drop or add.
     */
    public static List<String> textNestedNames(String bank, String canonical) {
        List<String> out = new ArrayList<>();
        if (canonical == null || !LOOKUP.containsKey(bank)) return out;
        String mine = norm(canonical);
        for (String other : new java.util.LinkedHashSet<>(LOOKUP.get(bank).values())) {
            if (other.equals(canonical) || nested(bank, canonical, other)) continue;
            String theirs = norm(other);
            if (theirs.contains(mine) || mine.contains(theirs)) out.add(other);
        }
        return out;
    }

    /**
     * Every other current product of this issuer that nests with this name in either direction: a word superset or
     * subset ("Regalia Gold" / "Regalia"), or a glued-suffix text match ("Zenith+" / "Zenith").
     */
    public static List<String> nestedNames(String bank, String canonical) {
        List<String> out = new ArrayList<>();
        if (canonical == null || !LOOKUP.containsKey(bank)) return out;
        Set<String> mine = new java.util.HashSet<>(List.of(key(bank, canonical).split(" ")));
        for (String other : new java.util.LinkedHashSet<>(LOOKUP.get(bank).values())) {
            if (other.equals(canonical)) continue;
            Set<String> theirs = new java.util.HashSet<>(List.of(key(bank, other).split(" ")));
            boolean words = (theirs.size() > mine.size() && theirs.containsAll(mine)) || (mine.size() > theirs.size() && mine.containsAll(theirs));
            if (words) out.add(other);
        }
        for (String t : textNestedNames(bank, canonical)) if (!out.contains(t)) out.add(t);
        return out;
    }

    /** One name's words are a strict subset of the other's. */
    public static boolean nested(String bank, String a, String b) {
        return longerNames(bank, a).contains(b) || longerNames(bank, b).contains(a);
    }

    /** A name that needs the issuer's name right next to it in running text. */
    static boolean needsIssuer(String canonical) {
        String n = norm(canonical);
        return tierOnly(canonical) || (!n.contains(" ") && COMMON_WORDS.contains(n));
    }

    /** Text needles of one issuer (normalised alias -> canonical), longest first. */
    static Map<String, String> needles(String bank) {
        return NEEDLES.getOrDefault(bank, Map.of());
    }

    static Set<String> issuers() {
        return LOOKUP.keySet();
    }
}
