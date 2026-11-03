package com.financeos.gmail.accountdetect.unlock;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The researched statement-password rules per bank id and kind (account | credit_card), from
 * {@code accountdetect/statement-password-rules.yml}. Patterns are token strings like "NAME4U+DDMM" in the order to try.
 */
public final class StatementPasswordRules {

    public static final String RESOURCE = "accountdetect/statement-password-rules.yml";

    /** @param kind "account" or "credit_card"; @param confidence official, secondary or unknown */
    public record Entry(String bank, String kind, String confidence, List<String> patterns) {}

    private static volatile StatementPasswordRules shared;

    private final List<Entry> entries;

    public StatementPasswordRules(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** The bundled table (loaded once). */
    public static StatementPasswordRules bundled() {
        StatementPasswordRules s = shared;
        if (s == null) {
            synchronized (StatementPasswordRules.class) {
                if (shared == null) shared = load(RESOURCE);
                s = shared;
            }
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    static StatementPasswordRules load(String resource) {
        try (InputStream in = StatementPasswordRules.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) ((Map<String, Object>) root).get("entries");
            List<Entry> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                List<String> patterns = new ArrayList<>();
                for (Object p : (List<Object>) m.get("patterns")) patterns.add(String.valueOf(p));
                out.add(new Entry(String.valueOf(m.get("bank")), String.valueOf(m.get("kind")), String.valueOf(m.get("confidence")), patterns));
            }
            return new StatementPasswordRules(out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * Patterns for this bank, in table order, deduplicated. {@code kind} is "account" or "credit_card"; null means both
     * (the mail did not say which).
     */
    public List<String> patterns(String bank, String kind) {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.bank().equals(bank) && (kind == null || e.kind().equals(kind))) out.addAll(e.patterns());
        }
        return new ArrayList<>(out);
    }

    /** Bank ids in the table (for tests). */
    public Set<String> banks() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Entry e : entries) m.put(e.bank(), true);
        return m.keySet();
    }
}
