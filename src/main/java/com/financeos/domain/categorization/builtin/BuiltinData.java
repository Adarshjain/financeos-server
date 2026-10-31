package com.financeos.domain.categorization.builtin;

import com.financeos.gmail.extract.Direction;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads and validates the YAML under {@code categorize/}. A broken file fails fast with a message naming the
 * file and entry: every regex must compile, every category must be in {@code categories.yml}, rule ids and
 * merchant names (after normalising) and MCC codes must be unique.
 */
final class BuiltinData {

    static final String ROOT = "categorize/";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Set<String> RULE_KEYS = Set.of("id", "category", "direction", "subject", "text", "counterparty",
            "from", "any", "maxAmount");
    private static final Pattern MCC = Pattern.compile("\\d{4}");

    /** A rule matches when every condition it names matches; a condition is a list of regexes, any one hits. */
    record Rule(String id, String category, Direction direction, List<Pattern> subject, List<Pattern> text,
                List<Pattern> counterparty, List<Pattern> from, List<Pattern> any, BigDecimal maxAmount) {}

    /** One normalised merchant name; {@code compact} is the same without spaces. */
    record Merchant(String name, String compact, String category) {}

    /** An inclusive MCC range. */
    record MccRange(int from, int to, String category) {}

    final Map<String, String> groupOf;
    final Set<String> honorifics;
    final Set<String> businessWords;
    final List<Rule> link;
    final List<Rule> keywords;
    final List<Merchant> merchants;
    /** Normalised names that look like a brand but can stand for anything: a match on them is no answer. */
    final Set<String> undecided;
    final Map<String, String> mccExact;
    final List<MccRange> mccRanges;

    private BuiltinData(Map<String, String> groupOf, Set<String> honorifics, Set<String> businessWords, List<Rule> link, List<Rule> keywords, List<Merchant> merchants, Set<String> undecided,
                        Map<String, String> mccExact, List<MccRange> mccRanges) {
        this.groupOf = groupOf;
        this.honorifics = honorifics;
        this.businessWords = businessWords;
        this.link = link;
        this.keywords = keywords;
        this.merchants = merchants;
        this.undecided = undecided;
        this.mccExact = mccExact;
        this.mccRanges = mccRanges;
    }

    static BuiltinData load() {
        return load(BuiltinData::read);
    }

    /** Loads from any source of parsed YAML roots by file name; the default reads the classpath. */
    static BuiltinData load(java.util.function.Function<String, Object> source) {
        Map<String, String> groupOf = categories(source.apply("categories.yml"));
        Set<String> categories = groupOf.keySet();
        List<Rule> link = rules("link.yml", source.apply("link.yml"), categories, false);
        List<Rule> keywords = rules("keywords.yml", source.apply("keywords.yml"), categories, true);
        Object merchantRoot = source.apply("merchants.yml");
        List<Merchant> merchants = merchants(merchantRoot, categories);
        Set<String> undecided = new HashSet<>();
        if (merchantRoot instanceof Map<?, ?> mr) {
            for (String name : strings(mr.get("undecided"))) {
                undecided.add(NameNormalizer.merchant(name));
            }
        }
        Map<String, String> mccExact = new HashMap<>();
        List<MccRange> mccRanges = new ArrayList<>();
        mcc(source.apply("mcc.yml"), categories, mccExact, mccRanges);
        Object people = source.apply("people.yml");
        if (!(people instanceof Map<?, ?> p)) {
            throw fail("people.yml", "expected a map with 'honorifics' and 'businessWords'");
        }
        Set<String> honorifics = lower(strings(p.get("honorifics")));
        Set<String> businessWords = lower(strings(p.get("businessWords")));
        if (honorifics.isEmpty() || businessWords.isEmpty()) {
            throw fail("people.yml", "honorifics and businessWords must not be empty");
        }
        return new BuiltinData(groupOf, honorifics, businessWords, List.copyOf(link), List.copyOf(keywords), List.copyOf(merchants), Set.copyOf(undecided),
                Map.copyOf(mccExact), List.copyOf(mccRanges));
    }

    private static Object read(String file) {
        try (InputStream in = BuiltinData.class.getClassLoader().getResourceAsStream(ROOT + file)) {
            if (in == null) {
                throw new IllegalStateException("Categoriser data " + file + ": not found on the classpath");
            }
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw fail(file, "invalid YAML: " + e.getMessage());
        }
    }

    /** Category name to its group, in file order. */
    private static Map<String, String> categories(Object root) {
        if (!(root instanceof Map<?, ?> m) || !(m.get("groups") instanceof Map<?, ?> groups)) {
            throw fail("categories.yml", "expected a map with a 'groups' map");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> g : groups.entrySet()) {
            for (String c : strings(g.getValue())) {
                if (out.put(c, String.valueOf(g.getKey())) != null) {
                    throw fail("categories.yml", "category listed twice: " + c);
                }
            }
        }
        return out;
    }

    private static List<Rule> rules(String file, Object root, Set<String> categories, boolean categoryRequired) {
        if (!(root instanceof Map<?, ?> m) || !(m.get("rules") instanceof List<?> list)) {
            throw fail(file, "expected a map with a 'rules' list");
        }
        List<Rule> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> r)) {
                throw fail(file, "every rule must be a map");
            }
            String id = string(r.get("id"));
            if (id == null || id.isBlank()) {
                throw fail(file, "rule without id");
            }
            if (!ids.add(id)) {
                throw fail(file, "rule id used twice: " + id);
            }
            for (Object key : r.keySet()) {
                if (!RULE_KEYS.contains(String.valueOf(key))) {
                    throw fail(file + " [" + id + "]", "unknown key '" + key + "'");
                }
            }
            String category = string(r.get("category"));
            if (categoryRequired != (category != null)) {
                throw fail(file + " [" + id + "]", categoryRequired ? "category is required" : "a link rule has no category");
            }
            if (category != null && !categories.contains(category)) {
                throw fail(file + " [" + id + "]", "unknown category '" + category + "'");
            }
            Direction direction = null;
            if (r.get("direction") != null) {
                try {
                    direction = Direction.valueOf(string(r.get("direction")));
                } catch (IllegalArgumentException e) {
                    throw fail(file + " [" + id + "]", "direction must be DEBIT or CREDIT");
                }
            }
            Rule rule = new Rule(id, category, direction, patterns(file, id, r, "subject"), patterns(file, id, r, "text"),
                    patterns(file, id, r, "counterparty"), patterns(file, id, r, "from"), patterns(file, id, r, "any"), maxAmount(file, id, r));
            if (rule.subject.isEmpty() && rule.text.isEmpty() && rule.counterparty.isEmpty() && rule.from.isEmpty()
                    && rule.any.isEmpty()) {
                throw fail(file + " [" + id + "]", "rule has no condition");
            }
            out.add(rule);
        }
        return out;
    }

    private static BigDecimal maxAmount(String file, String id, Map<?, ?> rule) {
        Object v = rule.get("maxAmount");
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw fail(file + " [" + id + "]", "maxAmount must be a number");
        }
    }

    private static List<Pattern> patterns(String file, String id, Map<?, ?> rule, String field) {
        List<Pattern> out = new ArrayList<>();
        for (String s : strings(rule.get(field))) {
            try {
                out.add(Pattern.compile(s, FLAGS));
            } catch (PatternSyntaxException e) {
                throw fail(file + " [" + id + "]", field + " regex does not compile: " + e.getDescription());
            }
        }
        return List.copyOf(out);
    }

    private static List<Merchant> merchants(Object root, Set<String> categories) {
        if (!(root instanceof Map<?, ?> m) || !(m.get("merchants") instanceof Map<?, ?> byCategory)) {
            throw fail("merchants.yml", "expected a map with a 'merchants' map");
        }
        Map<String, Merchant> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : byCategory.entrySet()) {
            String category = String.valueOf(e.getKey());
            if (!categories.contains(category)) {
                throw fail("merchants.yml", "unknown category '" + category + "'");
            }
            for (String name : strings(e.getValue())) {
                String key = NameNormalizer.merchant(name);
                if (key.isEmpty()) {
                    throw fail("merchants.yml", "name normalises to nothing: " + name);
                }
                Merchant previous = out.put(key, new Merchant(key, key.replace(" ", ""), category));
                if (previous != null) {
                    throw fail("merchants.yml", "merchant '" + key + "' listed twice (" + previous.category() + ", " + category + ")");
                }
            }
        }
        List<Merchant> sorted = new ArrayList<>(out.values());
        sorted.sort((a, b) -> Integer.compare(b.name().length(), a.name().length()));
        return sorted;
    }

    private static void mcc(Object root, Set<String> categories, Map<String, String> exact, List<MccRange> ranges) {
        if (!(root instanceof Map<?, ?> m) || !(m.get("codes") instanceof Map<?, ?> byCategory)) {
            throw fail("mcc.yml", "expected a map with a 'codes' map");
        }
        Map<Integer, String> taken = new TreeMap<>();
        for (Map.Entry<?, ?> e : byCategory.entrySet()) {
            String category = String.valueOf(e.getKey());
            if (!categories.contains(category)) {
                throw fail("mcc.yml", "unknown category '" + category + "'");
            }
            for (String code : strings(e.getValue())) {
                String[] parts = code.split("-");
                if (parts.length > 2 || !MCC.matcher(parts[0]).matches()
                        || (parts.length == 2 && !MCC.matcher(parts[1]).matches())) {
                    throw fail("mcc.yml", "bad MCC '" + code + "'");
                }
                int from = Integer.parseInt(parts[0]);
                int to = Integer.parseInt(parts[parts.length - 1]);
                if (to < from) {
                    throw fail("mcc.yml", "bad MCC range '" + code + "'");
                }
                if (parts.length == 1) {
                    if (taken.put(from, category) != null) {
                        throw fail("mcc.yml", "MCC " + code + " listed twice");
                    }
                    exact.put(parts[0], category);
                } else {
                    ranges.add(new MccRange(from, to, category));
                }
            }
        }
        for (MccRange a : ranges) {
            for (MccRange b : ranges) {
                if (a != b && a.from() <= b.to() && b.from() <= a.to()) {
                    throw fail("mcc.yml", "MCC ranges overlap: " + a.from() + "-" + a.to() + " and " + b.from() + "-" + b.to());
                }
            }
        }
    }

    /** Every category any data file can produce. */
    Set<String> producedCategories() {
        Set<String> out = new LinkedHashSet<>();
        keywords.forEach(r -> out.add(r.category()));
        merchants.forEach(x -> out.add(x.category()));
        out.addAll(mccExact.values());
        mccRanges.forEach(r -> out.add(r.category()));
        return out;
    }

    private static Set<String> lower(List<String> words) {
        Set<String> out = new HashSet<>();
        for (String w : words) {
            out.add(w.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    private static String string(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> strings(Object o) {
        if (o == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            for (Object x : c) {
                out.add(String.valueOf(x));
            }
        } else {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static IllegalStateException fail(String where, String message) {
        return new IllegalStateException("Categoriser data " + where + ": " + message);
    }
}
