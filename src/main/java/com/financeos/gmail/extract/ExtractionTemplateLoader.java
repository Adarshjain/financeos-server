package com.financeos.gmail.extract;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads and validates the per-bank YAML files under {@code gmail/extract/}. A broken file fails fast with
 * a message naming the file and template: every regex must compile, {@code amount} must be a named group,
 * ids must be unique, date formats must be valid java.time patterns.
 */
public final class ExtractionTemplateLoader {

    public static final String LOCATION = "classpath*:gmail/extract/*.yml";

    /** Matches the case-insensitive, Unicode-aware flags every template regex runs with. */
    static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Set<String> TEMPLATE_KEYS = Set.of("id", "senders", "subject", "body", "direction",
            "directionWords", "dateFormats", "currency", "counterpartyStrip", "counterpartyDefault");
    private static final Set<String> GROUPS = Set.of("amount", "currency", "date", "counterparty", "last4", "direction");

    private ExtractionTemplateLoader() {}

    /** Every template of every file on the classpath, files in name order, templates in file order. */
    public static List<ExtractionTemplate> loadAll() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            Arrays.sort(resources, (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
            List<ExtractionTemplate> all = new ArrayList<>();
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    all.addAll(parse(in, r.getFilename()));
                }
            }
            requireUniqueIds(all);
            return List.copyOf(all);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read extraction templates: " + e.getMessage(), e);
        }
    }

    /** Parses and validates one YAML file (ids are checked for uniqueness within the file). */
    public static List<ExtractionTemplate> parse(InputStream in, String fileName) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (RuntimeException e) {
            throw fail(fileName, null, "invalid YAML: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("templates") instanceof List<?> list)) {
            throw fail(fileName, null, "expected a map with a 'templates' list");
        }
        List<ExtractionTemplate> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> t)) {
                throw fail(fileName, null, "every template must be a map");
            }
            out.add(template(fileName, t));
        }
        requireUniqueIds(out);
        return out;
    }

    private static ExtractionTemplate template(String file, Map<?, ?> t) {
        String id = string(t.get("id"));
        if (id == null || id.isBlank()) {
            throw fail(file, null, "template without id");
        }
        for (Object key : t.keySet()) {
            if (!TEMPLATE_KEYS.contains(String.valueOf(key))) {
                throw fail(file, id, "unknown key '" + key + "'");
            }
        }
        List<String> senders = strings(t.get("senders"));
        if (senders.isEmpty()) {
            throw fail(file, id, "senders is required");
        }
        Pattern subject = t.get("subject") == null ? null : regex(file, id, "subject", string(t.get("subject")));
        if (t.get("body") == null) {
            throw fail(file, id, "body is required");
        }
        Pattern body = regex(file, id, "body", string(t.get("body")));
        Set<String> groups = body.namedGroups().keySet();
        if (!groups.contains("amount")) {
            throw fail(file, id, "body has no named group 'amount'");
        }
        for (String g : groups) {
            if (!GROUPS.contains(g)) {
                throw fail(file, id, "unknown named group '" + g + "'");
            }
        }

        String dir = string(t.get("direction"));
        Direction direction = null;
        Map<Direction, Set<String>> words = Map.of();
        if (dir == null) {
            throw fail(file, id, "direction is required (DEBIT, CREDIT or FROM_GROUP)");
        } else if (dir.equals("FROM_GROUP")) {
            if (!groups.contains("direction")) {
                throw fail(file, id, "FROM_GROUP needs a named group 'direction'");
            }
            words = directionWords(file, id, t.get("directionWords"));
        } else {
            try {
                direction = Direction.valueOf(dir);
            } catch (IllegalArgumentException e) {
                throw fail(file, id, "direction must be DEBIT, CREDIT or FROM_GROUP, got '" + dir + "'");
            }
        }

        List<String> formats = strings(t.get("dateFormats"));
        for (String f : formats) {
            try {
                ExtractParsing.dateFormatter(f);
            } catch (IllegalArgumentException e) {
                throw fail(file, id, "bad date format '" + f + "': " + e.getMessage());
            }
        }
        if (groups.contains("date") && formats.isEmpty()) {
            throw fail(file, id, "a 'date' group needs dateFormats");
        }
        String currency = t.get("currency") == null ? "INR" : string(t.get("currency")).trim().toUpperCase(Locale.ROOT);
        List<Pattern> strip = new ArrayList<>();
        for (String s : strings(t.get("counterpartyStrip"))) {
            strip.add(regex(file, id, "counterpartyStrip", s));
        }
        String counterpartyDefault = null;
        if (t.get("counterpartyDefault") != null) {
            counterpartyDefault = string(t.get("counterpartyDefault")).trim();
            if (counterpartyDefault.isEmpty()) {
                throw fail(file, id, "counterpartyDefault must not be blank");
            }
        }
        return new ExtractionTemplate(id, List.copyOf(senders), subject, body, direction, words, List.copyOf(formats),
                currency, List.copyOf(strip), counterpartyDefault);
    }

    private static Map<Direction, Set<String>> directionWords(String file, String id, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw fail(file, id, "FROM_GROUP needs directionWords: {DEBIT: [...], CREDIT: [...]}");
        }
        Map<Direction, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Direction d;
            try {
                d = Direction.valueOf(String.valueOf(e.getKey()));
            } catch (IllegalArgumentException ex) {
                throw fail(file, id, "directionWords key must be DEBIT or CREDIT, got '" + e.getKey() + "'");
            }
            Set<String> lower = new HashSet<>();
            for (String w : strings(e.getValue())) {
                lower.add(w.trim().toLowerCase(Locale.ROOT));
            }
            out.put(d, Set.copyOf(lower));
        }
        if (out.isEmpty()) {
            throw fail(file, id, "directionWords is empty");
        }
        return Map.copyOf(out);
    }

    private static Pattern regex(String file, String id, String field, String source) {
        try {
            return Pattern.compile(source, FLAGS);
        } catch (PatternSyntaxException e) {
            throw fail(file, id, field + " regex does not compile: " + e.getDescription());
        }
    }

    private static void requireUniqueIds(Collection<ExtractionTemplate> templates) {
        Set<String> seen = new HashSet<>();
        for (ExtractionTemplate t : templates) {
            if (!seen.add(t.id())) {
                throw new IllegalStateException("Extraction template id used twice: " + t.id());
            }
        }
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

    private static IllegalStateException fail(String file, String id, String message) {
        return new IllegalStateException("Extraction template " + file + (id == null ? "" : " [" + id + "]") + ": " + message);
    }
}
