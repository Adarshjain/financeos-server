package com.financeos.gmail.ingest;

import com.financeos.gmail.extract.ExtractedTransaction;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Field-by-field comparison of the offline engine's answer with the AI's, for the shadow run only. */
final class ExtractShadowComparator {

    static final String ENGINE_ONLY = "ENGINE_ONLY";
    static final String AI_ONLY = "AI_ONLY";
    static final String AI_FAILED = "AI_FAILED";

    private static final Pattern NON_TOKEN = Pattern.compile("[^a-z0-9@.]+");

    private ExtractShadowComparator() {
    }

    /** Differing field names (amount, currency, direction, date, last4, counterparty); empty when all agree. */
    static List<String> differingFields(ExtractedTransaction engine, GeminiExtractionResult ai) {
        List<String> out = new ArrayList<>();
        if (!amountEquals(engine, ai)) out.add("amount");
        if (!equalsIgnoreCase(engine.currency(), ai.currency())) out.add("currency");
        if (!equalsIgnoreCase(engine.direction() == null ? null : engine.direction().name(), ai.direction())) out.add("direction");
        if (engine.date() == null ? ai.date() != null : !engine.date().equals(ai.date())) out.add("date");
        if (!java.util.Objects.equals(last4(engine.last4()), last4(ai.accountLast4()))) out.add("last4");
        if (!counterpartyEquals(engine.counterparty(), ai.description())) out.add("counterparty");
        return out;
    }

    /** The stored mismatch value, or null when engine and AI agree (or both say no transaction). */
    static String mismatch(Optional<ExtractedTransaction> engine, GeminiExtractionResult ai) {
        if (!ai.isSuccess()) return AI_FAILED;
        if (engine.isPresent() && !ai.isTransaction()) return ENGINE_ONLY;
        if (engine.isEmpty() && ai.isTransaction()) return AI_ONLY;
        if (engine.isEmpty()) return null;
        List<String> fields = differingFields(engine.get(), ai);
        return fields.isEmpty() ? null : String.join(",", fields);
    }

    /** Metric result kind: match, mismatch, engine_only, ai_only, ai_failed or both_none. */
    static String resultKind(Optional<ExtractedTransaction> engine, GeminiExtractionResult ai) {
        String m = mismatch(engine, ai);
        if (AI_FAILED.equals(m)) return "ai_failed";
        if (ENGINE_ONLY.equals(m)) return "engine_only";
        if (AI_ONLY.equals(m)) return "ai_only";
        if (engine.isEmpty()) return "both_none";
        return m == null ? "match" : "mismatch";
    }

    private static boolean amountEquals(ExtractedTransaction engine, GeminiExtractionResult ai) {
        if (engine.amount() == null || ai.amount() == null) return engine.amount() == ai.amount();
        return engine.amount().abs().compareTo(ai.amount().abs()) == 0;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static String last4(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
    }

    static boolean counterpartyEquals(String engine, String aiDescription) {
        String a = normalize(engine);
        String b = normalize(aiDescription);
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return String.join("", NON_TOKEN.split(s.toLowerCase(Locale.ROOT)));
    }
}
