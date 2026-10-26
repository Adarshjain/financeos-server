package com.financeos.domain.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rewrites the account names in a saved report definition's top-level {@code filters} to account
 * ids (V111), so renaming an account no longer breaks the report. Pure JSON in, JSON out: the
 * migration and its tests run this same code.
 *
 * <p>Only {@code is}/{@code is_not}/{@code in}/{@code not_in} filters on {@code account} are touched.
 * A value that is already a UUID stays. A name with one account becomes its id; a name shared by
 * several accounts stays a name under every operator (expanding it to several ids would break the
 * billing-cycle "exactly one account" rule, and the name still matches correctly); an unknown name
 * stays (it matches nothing already).
 * Idempotent: ids are never rewritten, so a second pass changes nothing.
 */
public final class AccountFilterIdRewrite {

    // Exact decimals: a rewritten definition keeps every number as written (1234.50 stays 1234.50).
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private static final Set<String> OPERATORS = Set.of("is", "is_not", "in", "not_in");

    private AccountFilterIdRewrite() {
    }

    /** As {@link #rewrite(String, Map, AtomicInteger)}, without counting the names left. */
    public static String rewrite(String definitionJson, Map<String, List<String>> idsByName) {
        return rewrite(definitionJson, idsByName, new AtomicInteger());
    }

    /**
     * The definition with account names replaced by ids, or null when nothing changed.
     *
     * @param idsByName account name (exact, case-sensitive, as the filter compares it) to the ids of
     *                  the report owner's accounts with that name (only a single id is used)
     * @param namesLeft incremented per account-filter name that stays a name
     */
    public static String rewrite(String definitionJson, Map<String, List<String>> idsByName, AtomicInteger namesLeft) {
        if (definitionJson == null) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(definitionJson);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.has("filters") || !root.get("filters").isArray()) {
            return null;
        }
        boolean changed = false;
        for (JsonNode filter : root.get("filters")) {
            if (!(filter instanceof ObjectNode f) || !"account".equals(f.path("field").asText(null))
                    || !OPERATORS.contains(f.path("operator").asText(null))) {
                continue;
            }
            JsonNode value = f.get("value");
            if (value != null && value.isTextual()) {
                String id = resolve(value.asText(), idsByName, namesLeft);
                if (id != null) {
                    f.set("value", JsonNodeFactory.instance.textNode(id));
                    changed = true;
                }
            } else if (value != null && value.isArray()) {
                ArrayNode out = JsonNodeFactory.instance.arrayNode();
                boolean arrayChanged = false;
                for (JsonNode element : value) {
                    String id = element.isTextual() ? resolve(element.asText(), idsByName, namesLeft) : null;
                    if (id == null) {
                        out.add(element);
                    } else {
                        out.add(id);
                        arrayChanged = true;
                    }
                }
                if (arrayChanged) {
                    f.set("value", out);
                    changed = true;
                }
            }
        }
        return changed ? root.toString() : null;
    }

    /** The id replacing {@code name}, or null when it stays (already an id, unknown, or shared by several accounts). */
    private static String resolve(String name, Map<String, List<String>> idsByName, AtomicInteger namesLeft) {
        if (isUuid(name)) {
            return null;
        }
        List<String> ids = idsByName.get(name);
        if (ids == null || ids.size() != 1) {
            namesLeft.incrementAndGet();
            return null;
        }
        return ids.get(0);
    }

    private static boolean isUuid(String value) {
        try {
            return value.length() == 36 && UUID.fromString(value) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
