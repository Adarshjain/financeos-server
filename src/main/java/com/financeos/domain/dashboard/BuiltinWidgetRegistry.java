package com.financeos.domain.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.ReportType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalog of built-in dashboard widgets. A {@code template} entry is a fixed report
 * definition over a datasource (run through the report engine with the widget's params
 * substituted); a {@code component} entry is rendered by the client from its own endpoint and
 * has no report data. Keys are stable strings stored in dashboard widget JSON.
 */
@Component
public class BuiltinWidgetRegistry {

    public static final String KIND_TEMPLATE = "template";
    public static final String KIND_COMPONENT = "component";

    public static final String PARAM_INT = "int";
    public static final String PARAM_UUID = "uuid";

    public static final String NET_WORTH = "net_worth";
    public static final String ATTENTION = "attention";
    public static final String UPCOMING = "upcoming";
    public static final String BILLS_DUE = "bills_due";

    /** Default horizon of the {@code upcoming} widget, in days. */
    public static final int UPCOMING_DEFAULT_DAYS = 14;

    /** One accepted parameter: {@code type} is {@link #PARAM_INT} or {@link #PARAM_UUID}. */
    public record ParamSpec(String name, String type, boolean required, @Nullable JsonNode defaultValue,
                            @Nullable Integer min, @Nullable Integer max) {
    }

    /** Substitutes validated params into a deep copy of the template definition. */
    @FunctionalInterface
    public interface ParamApplier {
        void apply(ObjectNode definition, @Nullable JsonNode params);
    }

    /**
     * A registry entry. {@code templateType}, {@code datasource}, {@code templateDefinition} and
     * {@code paramApplier} are null for components.
     */
    public record Entry(
            String key,
            String label,
            String description,
            int minW,
            String kind,
            @Nullable ReportType templateType,
            @Nullable String datasource,
            @Nullable JsonNode templateDefinition,
            @Nullable String href,
            List<ParamSpec> params,
            @Nullable ParamApplier paramApplier) {

        public boolean isTemplate() {
            return KIND_TEMPLATE.equals(kind);
        }
    }

    private final ObjectMapper mapper;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public BuiltinWidgetRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
        register(netWorth());
        register(attention());
        register(upcoming());
        register(billsDue());
    }

    private void register(Entry entry) {
        entries.put(entry.key(), entry);
    }

    // ------------------------------------------------------------------ lookup

    /** Every entry, in catalog order. */
    public List<Entry> all() {
        return List.copyOf(entries.values());
    }

    public Optional<Entry> find(@Nullable String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(entries.get(key));
    }

    public boolean isKnown(@Nullable String key) {
        return key != null && entries.containsKey(key);
    }

    /** The entry for {@code key}, or 404. */
    public Entry require(String key) {
        return find(key).orElseThrow(() -> new ResourceNotFoundException("Built-in widget", key));
    }

    // ------------------------------------------------------------------ params

    /**
     * Checks {@code params} against the entry's schema: a JSON object (or absent), only declared
     * names, required ones present, ints integral and within [min, max], uuids parseable.
     */
    public void validateParams(Entry entry, @Nullable JsonNode params) {
        boolean absent = params == null || params.isNull();
        if (!absent && !params.isObject()) {
            throw new ValidationException("Built-in widget '" + entry.key() + "' params must be a JSON object");
        }
        if (!absent) {
            for (var names = params.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (entry.params().stream().noneMatch(p -> p.name().equals(name))) {
                    throw new ValidationException(
                            "Built-in widget '" + entry.key() + "' does not accept param '" + name + "'");
                }
            }
        }
        for (ParamSpec spec : entry.params()) {
            JsonNode value = absent ? null : params.get(spec.name());
            if (value == null || value.isNull()) {
                if (spec.required()) {
                    throw new ValidationException(
                            "Built-in widget '" + entry.key() + "' requires param '" + spec.name() + "'");
                }
                continue;
            }
            validateParam(entry, spec, value);
        }
    }

    private static void validateParam(Entry entry, ParamSpec spec, JsonNode value) {
        String prefix = "Built-in widget '" + entry.key() + "' param '" + spec.name() + "'";
        switch (spec.type()) {
            case PARAM_INT -> {
                if (!value.isIntegralNumber()) {
                    throw new ValidationException(prefix + " must be an integer");
                }
                int n = value.asInt();
                if ((spec.min() != null && n < spec.min()) || (spec.max() != null && n > spec.max())) {
                    throw new ValidationException(prefix + " must be between " + spec.min() + " and " + spec.max());
                }
            }
            case PARAM_UUID -> {
                if (!value.isTextual()) {
                    throw new ValidationException(prefix + " must be a uuid string");
                }
                try {
                    UUID.fromString(value.asText());
                } catch (IllegalArgumentException e) {
                    throw new ValidationException(prefix + " must be a uuid string");
                }
            }
            default -> throw new IllegalStateException("Unknown built-in param type: " + spec.type());
        }
    }

    /**
     * The template definition to run for {@code entry} with {@code params} applied (defaults when
     * absent). Validates the params first. 400 for a component entry (it has no report data).
     */
    public JsonNode resolveDefinition(Entry entry, @Nullable JsonNode params) {
        if (!entry.isTemplate() || entry.templateDefinition() == null) {
            throw new ValidationException(
                    "Built-in widget '" + entry.key() + "' is a component and has no report data");
        }
        validateParams(entry, params);
        ObjectNode definition = entry.templateDefinition().deepCopy();
        if (entry.paramApplier() != null) {
            entry.paramApplier().apply(definition, params == null || params.isNull() ? null : params);
        }
        return definition;
    }

    /** An int param's value, falling back to {@code fallback} when absent. */
    public static int intParam(@Nullable JsonNode params, String name, int fallback) {
        JsonNode value = params == null ? null : params.get(name);
        return value != null && value.isIntegralNumber() ? value.asInt() : fallback;
    }

    // ------------------------------------------------------------------ entries

    private Entry netWorth() {
        ObjectNode def = mapper.createObjectNode();
        def.put("measure", "signedValue");
        def.put("aggregation", "sum");
        def.putArray("filters");
        return new Entry(NET_WORTH, "Net worth", "Assets minus liabilities across every account.",
                50, KIND_TEMPLATE, ReportType.KPI, "net_worth", def, "/accounts", List.of(), null);
    }

    /**
     * The Inbox on Home: a component rendered by the client from GET /inbox (counts per section plus
     * the most urgent rows with their actions). The attention datasource stays available for
     * user-built reports.
     */
    private Entry attention() {
        return new Entry(ATTENTION, "Inbox", "What needs you now, most urgent first, with one-tap actions.",
                50, KIND_COMPONENT, null, null, null, "/inbox", List.of(), null);
    }

    private Entry upcoming() {
        ObjectNode def = mapper.createObjectNode();
        def.put("mode", "raw");
        ArrayNode columns = def.putArray("columns");
        columns.add("dueDate").add("title").add("amount");
        ArrayNode filters = def.putArray("filters");
        ObjectNode horizon = filters.addObject();
        horizon.put("field", "dueDate");
        horizon.put("operator", "next_x_days");
        horizon.putObject("value").put("amount", UPCOMING_DEFAULT_DAYS);
        ArrayNode sort = def.putArray("sort");
        ObjectNode byDue = sort.addObject();
        byDue.put("key", "dueDate");
        byDue.put("direction", "asc");
        ParamSpec days = new ParamSpec("days", PARAM_INT, false, mapper.getNodeFactory().numberNode(UPCOMING_DEFAULT_DAYS), 1, 90);
        return new Entry(UPCOMING, "Upcoming", "Bills, EMIs and expected returns due in the next few days.",
                100, KIND_TEMPLATE, ReportType.TABLE, "obligations", def, "/upcoming", List.of(days),
                (definition, params) -> {
                    int amount = intParam(params, "days", UPCOMING_DEFAULT_DAYS);
                    for (JsonNode filter : definition.withArray("filters")) {
                        if ("dueDate".equals(filter.path("field").asText())
                                && "next_x_days".equals(filter.path("operator").asText())
                                && filter.isObject()) {
                            ((ObjectNode) filter).putObject("value").put("amount", amount);
                        }
                    }
                });
    }

    private Entry billsDue() {
        ParamSpec accountId = new ParamSpec("accountId", PARAM_UUID, false, null, null, null);
        return new Entry(BILLS_DUE, "Bills due", "Credit-card bills with their due dates and the actions to settle them.",
                100, KIND_COMPONENT, null, null, null, null, List.of(accountId), null);
    }
}
