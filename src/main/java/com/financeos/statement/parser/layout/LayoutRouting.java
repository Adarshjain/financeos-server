package com.financeos.statement.parser.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ParseSnapshot;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.StatementParseEngine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which reader takes a statement: per-profile modes (default SHADOW), an optional forced reader for the corpus tool and
 * the shadow-comparison hook. Static, like the parser's other settings.
 */
public final class LayoutRouting {

    /** The profile that matched and the mode it runs in; mode OFF (profile null) means v1 only. */
    public record Decision(LayoutProfile profile, ReaderMode mode) {
    }

    /** Forced reader: V1 ignores every profile, LAYOUT uses the matching profile as if ACTIVE. */
    public enum Forced { V1, LAYOUT }

    private static final Map<String, ReaderMode> MODES = new ConcurrentHashMap<>();
    private static volatile ReaderMode defaultMode = ReaderMode.SHADOW;
    private static volatile Forced forced;
    private static volatile ShadowListener listener;
    private static volatile LayoutRegistry registry = LayoutRegistry.defaults();

    private LayoutRouting() {
    }

    public static void setMode(String profileId, ReaderMode mode) {
        if (mode == null) {
            MODES.remove(profileId);
        } else {
            MODES.put(profileId, mode);
        }
    }

    public static void setDefaultMode(ReaderMode mode) {
        defaultMode = mode == null ? ReaderMode.SHADOW : mode;
    }

    public static ReaderMode modeOf(String profileId) {
        return MODES.getOrDefault(profileId, defaultMode);
    }

    /** "v1", "layout", or null/blank for the configured modes. */
    public static void setForcedReader(String text) {
        if (text == null || text.isBlank()) {
            forced = null;
        } else if ("v1".equalsIgnoreCase(text.strip())) {
            forced = Forced.V1;
        } else if ("layout".equalsIgnoreCase(text.strip())) {
            forced = Forced.LAYOUT;
        } else {
            throw new IllegalArgumentException("reader must be v1 or layout: " + text);
        }
    }

    public static void setShadowListener(ShadowListener l) {
        listener = l;
    }

    public static void setRegistry(LayoutRegistry r) {
        registry = r == null ? LayoutRegistry.defaults() : r;
    }

    public static LayoutRegistry registry() {
        return registry;
    }

    /** Back to the defaults (SHADOW for every profile, no forced reader, no listener, default registry). */
    public static void reset() {
        MODES.clear();
        defaultMode = ReaderMode.SHADOW;
        forced = null;
        listener = null;
        registry = LayoutRegistry.defaults();
    }

    public static boolean hasListener() {
        return listener != null;
    }

    public static boolean forcedLayout() {
        return forced == Forced.LAYOUT;
    }

    /** Picks the profile and mode for a document; never throws. The issuer is only worked out when a header matched. */
    public static Decision decide(List<Line> lines) {
        Decision none = new Decision(null, ReaderMode.OFF);
        if (forced == Forced.V1) {
            return none;
        }
        try {
            LayoutProfile p = registry.matchLazy(lines, () -> StatementParseEngine.issuerOf(lines));
            if (p == null) {
                return none;
            }
            ReaderMode mode = forced == Forced.LAYOUT ? ReaderMode.ACTIVE : modeOf(p.id());
            return mode == ReaderMode.OFF ? none : new Decision(p, mode);
        } catch (RuntimeException | StackOverflowError e) {
            return none;
        }
    }

    /** Tells the listener that an ACTIVE result did not pass the guard and v1 was consulted. */
    public static void reportFallback(String layoutId) {
        notifyListener(new ShadowListener.Result(layoutId, ShadowListener.Kind.FALLBACK, 0, 0, List.of()));
    }

    /** Compares the v1 result with the shadow one (null = the profiled reader failed) and tells the listener. */
    public static void reportShadow(String layoutId, ParsedStatement v1, ParsedStatement shadow) {
        if (listener == null) {
            return;
        }
        try {
            notifyListener(compare(layoutId, v1, shadow));
        } catch (RuntimeException e) {
            // never affects parsing
        }
    }

    private static void notifyListener(ShadowListener.Result r) {
        ShadowListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onComparison(r);
        } catch (RuntimeException e) {
            // a misbehaving listener never affects parsing
        }
    }

    /**
     * Fixed ignore list: {@code meta.bank} (v1 guesses it from page text, a profile states it), every row's
     * {@code signSource} (the readers label signs differently) and {@code layoutId} / {@code reader} (they name the
     * reader). A known-neutral difference must not mask a real one.
     */
    public static ShadowListener.Result compare(String layoutId, ParsedStatement v1, ParsedStatement shadow) {
        if (shadow == null) {
            return new ShadowListener.Result(layoutId, ShadowListener.Kind.FAILED, 0, 0, List.of());
        }
        ObjectNode a = ParseSnapshot.of(v1);
        ObjectNode b = ParseSnapshot.of(shadow);
        a.remove(List.of("layoutId", "reader"));
        b.remove(List.of("layoutId", "reader"));
        ((ObjectNode) a.get("meta")).remove("bank");
        ((ObjectNode) b.get("meta")).remove("bank");
        List<String> names = new ArrayList<>();
        for (String section : new String[]{"meta", "parseInfo", "summaryFields"}) {
            differingKeys(section, a.get(section), b.get(section), names);
        }
        if (!a.path("statementType").equals(b.path("statementType"))) {
            names.add("statementType");
        }
        JsonNode ta = a.get("transactions");
        JsonNode tb = b.get("transactions");
        int rows = Math.abs(ta.size() - tb.size());
        for (int i = 0; i < Math.min(ta.size(), tb.size()); i++) {
            ObjectNode ra = ((ObjectNode) ta.get(i)).deepCopy();
            ObjectNode rb = ((ObjectNode) tb.get(i)).deepCopy();
            ra.remove("signSource");
            rb.remove("signSource");
            if (!ra.equals(rb)) {
                rows++;
            }
        }
        boolean same = rows == 0 && names.isEmpty();
        return new ShadowListener.Result(layoutId, same ? ShadowListener.Kind.IDENTICAL : ShadowListener.Kind.DIFFERENT,
                rows, names.size(), List.copyOf(names));
    }

    private static void differingKeys(String section, JsonNode x, JsonNode y, List<String> out) {
        TreeSet<String> keys = new TreeSet<>();
        for (Iterator<String> it = x.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        for (Iterator<String> it = y.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        for (String k : keys) {
            if (!x.path(k).equals(y.path(k))) {
                out.add(section + "." + k);
            }
        }
    }
}
