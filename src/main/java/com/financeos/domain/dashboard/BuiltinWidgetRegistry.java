package com.financeos.domain.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.report.ReportType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

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
    /** A string that must be one of the spec's {@code options}. */
    public static final String PARAM_ENUM = "enum";
    /** An ordered JSON array of strings, at most {@code maxItems}, each matching {@code itemPattern}. */
    public static final String PARAM_STRING_LIST = "string_list";

    /** What a {@link #PARAM_UUID} param points at, so the editor can offer the right picker. */
    public static final String REF_CREDIT_CARD = "credit_card";
    public static final String REF_ACCOUNT = "account";
    public static final String REF_LOAN = "loan";

    /** Picker categories (the client adds "Your reports" for saved reports). */
    public static final String CATEGORY_OVERVIEW = "overview";
    public static final String CATEGORY_CARDS_REWARDS = "cards_rewards";
    public static final String CATEGORY_SPENDING = "spending";
    public static final String CATEGORY_INVESTMENTS = "investments";
    public static final String CATEGORY_LOANS_LENDING = "loans_lending";
    public static final String CATEGORY_SHORTCUTS = "shortcuts";
    public static final List<String> CATEGORIES = List.of(CATEGORY_OVERVIEW, CATEGORY_CARDS_REWARDS,
            CATEGORY_SPENDING, CATEGORY_INVESTMENTS, CATEGORY_LOANS_LENDING, CATEGORY_SHORTCUTS);

    public static final String NET_WORTH = "net_worth";
    public static final String ATTENTION = "attention";
    public static final String UPCOMING = "upcoming";
    public static final String BILLS_DUE = "bills_due";
    public static final String CARD_UTILISATION = "card_utilisation";
    public static final String MILESTONE_PROGRESS = "milestone_progress";
    public static final String CAP_HEADROOM = "cap_headroom";
    public static final String PORTFOLIO_SNAPSHOT = "portfolio_snapshot";
    public static final String TOP_MOVERS = "top_movers";
    public static final String ALLOCATION = "allocation";
    public static final String TAX_HARVEST = "tax_harvest";
    public static final String SPEND_HEATMAP = "spend_heatmap";
    public static final String LOAN_PAYOFF = "loan_payoff";
    public static final String LENDING_BALANCES = "lending_balances";
    public static final String ACCOUNT_TILE = "account_tile";
    public static final String EMERGENCY_FUND = "emergency_fund";
    public static final String REWARDS_EARNED = "rewards_earned";
    public static final String SHORTCUTS = "shortcuts";

    /** Client renderers of the template entries' data. */
    public static final String VIEW_PROGRESS_LIST = "progress_list";
    public static final String VIEW_CAP_LIST = "cap_list";
    public static final String VIEW_ALLOCATION = "allocation";
    public static final String VIEW_HEATMAP = "heatmap";
    public static final String VIEW_REWARDS_FY = "rewards_fy";

    /** Default and bounds of {@code top_movers}' {@code n}. */
    public static final int TOP_MOVERS_DEFAULT = 5;
    /** Default and bounds of {@code spend_heatmap}'s {@code months}. */
    public static final int HEATMAP_DEFAULT_MONTHS = 6;
    /** Most items a {@code shortcuts} widget holds. */
    public static final int SHORTCUTS_MAX_ITEMS = 12;
    /**
     * One shortcut (the client checks the same pattern):
     * <ul>
     *   <li>{@code page:/<path>} — a same-app path: {@code /} then up to 199 characters of
     *       {@code [A-Za-z0-9/_\-.?=&]} that do not start with {@code /} (so never a
     *       protocol-relative {@code //host}) and contain no {@code :} or {@code \} (so never a
     *       scheme such as {@code javascript:}); {@code page:/} alone is the home page;</li>
     *   <li>{@code action:<id>} — an action id of lower-case letters and dashes;</li>
     *   <li>{@code account:} / {@code report:} / {@code dashboard:} — a UUID.</li>
     * </ul>
     */
    public static final String SHORTCUT_ITEM_PATTERN = "^(?:page:/(?:[A-Za-z0-9_\\-.?=&][A-Za-z0-9/_\\-.?=&]{0,198})?"
            + "|action:[a-z-]+"
            + "|(?:account|report|dashboard):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$";
    public static final List<String> SHORTCUTS_DEFAULT = List.of(
            "action:add-transaction", "page:/transactions/review", "page:/transactions/import", "page:/upcoming");

    static final String NEEDS_CARD = "Needs a credit card";
    static final String ADD_CARD = "Add a credit card first";
    static final String NEEDS_HOLDINGS = "Needs investments";
    static final String ADD_HOLDINGS = "Add an investment first";
    static final String NEEDS_ACCOUNT = "Needs an account";
    static final String ADD_ACCOUNT = "Add an account first";

    /** Default horizon of the {@code upcoming} widget, in days. */
    public static final int UPCOMING_DEFAULT_DAYS = 14;

    /**
     * One accepted parameter. {@code type} is {@link #PARAM_INT} (bounded by {@code min}/{@code max}),
     * {@link #PARAM_UUID} ({@code ref} names what it points at: {@link #REF_CREDIT_CARD},
     * {@link #REF_ACCOUNT} or {@link #REF_LOAN}), {@link #PARAM_ENUM} (one of {@code options}) or
     * {@link #PARAM_STRING_LIST} (at most {@code maxItems} strings, each matching {@code itemPattern}).
     */
    public record ParamSpec(String name, String type, boolean required, @Nullable JsonNode defaultValue,
                            @Nullable Integer min, @Nullable Integer max, @Nullable String ref,
                            @Nullable List<String> options, @Nullable Integer maxItems,
                            @Nullable String itemPattern) {

        /** An int or uuid param without a ref (the original shape). */
        public ParamSpec(String name, String type, boolean required, @Nullable JsonNode defaultValue,
                         @Nullable Integer min, @Nullable Integer max) {
            this(name, type, required, defaultValue, min, max, null, null, null, null);
        }

        public static ParamSpec intParam(String name, boolean required, @Nullable JsonNode defaultValue,
                                         int min, int max) {
            return new ParamSpec(name, PARAM_INT, required, defaultValue, min, max, null, null, null, null);
        }

        public static ParamSpec uuidRef(String name, String ref, boolean required) {
            return new ParamSpec(name, PARAM_UUID, required, null, null, null, ref, null, null, null);
        }

        public static ParamSpec enumParam(String name, boolean required, @Nullable JsonNode defaultValue,
                                          List<String> options) {
            return new ParamSpec(name, PARAM_ENUM, required, defaultValue, null, null, null, List.copyOf(options),
                    null, null);
        }

        public static ParamSpec stringList(String name, boolean required, @Nullable JsonNode defaultValue,
                                           int maxItems, String itemPattern) {
            return new ParamSpec(name, PARAM_STRING_LIST, required, defaultValue, null, null, null, null, maxItems,
                    itemPattern);
        }
    }

    /** Substitutes validated params into a deep copy of the template definition. */
    @FunctionalInterface
    public interface ParamApplier {
        void apply(ObjectNode definition, @Nullable JsonNode params);
    }

    /**
     * A registry entry. {@code templateType}, {@code datasource}, {@code templateDefinition} and
     * {@code paramApplier} are null for components.
     *
     * @param description  the long picker text (shown only in the picker)
     * @param category     one of {@link #CATEGORIES}
     * @param subtitle     the short line under the title on the dashboard; null for none
     * @param requires     a "Needs …" sentence for the picker; null when nothing is needed
     * @param view         the client renderer for a template's data (e.g. {@code progress_list}); null
     *                     for the default rendering of its report type
     * @param availability per-user check behind {@code unavailableReason}
     */
    public record Entry(
            String key,
            String label,
            String description,
            String category,
            @Nullable String subtitle,
            @Nullable String requires,
            @Nullable String view,
            int minW,
            String kind,
            @Nullable ReportType templateType,
            @Nullable String datasource,
            @Nullable JsonNode templateDefinition,
            @Nullable String href,
            List<ParamSpec> params,
            @Nullable ParamApplier paramApplier,
            BuiltinAvailability availability) {

        /** An always-available {@link #CATEGORY_OVERVIEW} entry with no subtitle, requirement or view. */
        public Entry(String key, String label, String description, int minW, String kind,
                     @Nullable ReportType templateType, @Nullable String datasource,
                     @Nullable JsonNode templateDefinition, @Nullable String href, List<ParamSpec> params,
                     @Nullable ParamApplier paramApplier) {
            this(key, label, description, CATEGORY_OVERVIEW, null, null, null, minW, kind, templateType,
                    datasource, templateDefinition, href, params, paramApplier, BuiltinAvailability.ALWAYS);
        }

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
        register(cardUtilisation());
        register(milestoneProgress());
        register(capHeadroom());
        register(rewardsEarned());
        register(spendHeatmap());
        register(portfolioSnapshot());
        register(topMovers());
        register(allocation());
        register(taxHarvest());
        register(loanPayoff());
        register(lendingBalances());
        register(accountTile());
        register(emergencyFund());
        register(shortcuts());
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
     * names, required ones present, ints integral and within [min, max], uuids parseable, enums one
     * of their options, string lists within maxItems with every item matching the item pattern.
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
            case PARAM_ENUM -> {
                List<String> options = spec.options() == null ? List.of() : spec.options();
                if (!value.isTextual() || !options.contains(value.asText())) {
                    throw new ValidationException(prefix + " must be one of " + options);
                }
            }
            case PARAM_STRING_LIST -> {
                if (!value.isArray()) {
                    throw new ValidationException(prefix + " must be a list of strings");
                }
                if (spec.maxItems() != null && value.size() > spec.maxItems()) {
                    throw new ValidationException(prefix + " allows at most " + spec.maxItems() + " items");
                }
                Pattern pattern = spec.itemPattern() == null ? null : Pattern.compile(spec.itemPattern());
                for (JsonNode item : value) {
                    if (!item.isTextual()) {
                        throw new ValidationException(prefix + " must be a list of strings");
                    }
                    if (pattern != null && !pattern.matcher(item.asText()).matches()) {
                        throw new ValidationException(prefix + " has an invalid item: " + item.asText());
                    }
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

    /**
     * True when {@link #resolveDefinition} pins today's window as fixed dates: the "window open
     * today" of {@code milestone_progress} / {@code cap_headroom} (start on or before today, end on
     * or after it) has no relative date preset, so a definition saved from it is a snapshot of
     * today's window. Every other template's date filters are relative presets
     * ({@code next_x_days}, {@code last_x_months}, {@code current_fy}) and stay current.
     */
    public static boolean pinsTodaysWindow(Entry entry) {
        return MILESTONE_PROGRESS.equals(entry.key()) || CAP_HEADROOM.equals(entry.key());
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
        return new Entry(NET_WORTH, "Net worth",
                "Everything you own minus everything you owe, across bank accounts, cards, investments, loans "
                        + "and money lent. Tap the number to see how each account adds up.",
                CATEGORY_OVERVIEW, "Assets minus liabilities", null, null,
                50, KIND_TEMPLATE, ReportType.KPI, "net_worth", def, "/accounts", List.of(), null,
                BuiltinAvailability.ALWAYS);
    }

    /**
     * The Inbox on Home: a component rendered by the client from GET /inbox (counts per section plus
     * the most urgent rows with their actions). The attention datasource stays available for
     * user-built reports.
     */
    private Entry attention() {
        return new Entry(ATTENTION, "Inbox",
                "The few things that need you now \u2014 bills, EMIs, missing statements, Gmail reconnects and "
                        + "transactions waiting for review \u2014 with the action right on the row.",
                CATEGORY_OVERVIEW, "What needs you now", null, null,
                50, KIND_COMPONENT, null, null, null, "/inbox", List.of(), null, BuiltinAvailability.ALWAYS);
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
        ParamSpec days = ParamSpec.intParam("days", false,
                mapper.getNodeFactory().numberNode(UPCOMING_DEFAULT_DAYS), 1, 90);
        return new Entry(UPCOMING, "Upcoming",
                "Bills, EMIs, expected statements and lending returns due in the next few days, soonest first.",
                CATEGORY_OVERVIEW, "Due in the next few days", null, null,
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
                },
                BuiltinAvailability.ALWAYS);
    }

    private Entry billsDue() {
        ParamSpec accountId = ParamSpec.uuidRef("accountId", REF_CREDIT_CARD, false);
        return new Entry(BILLS_DUE, "Bills due",
                "Each card's bill as it moves from unbilled spend to statement to paid, with Mark paid on the card. "
                        + "Add one per card or one for all.",
                CATEGORY_CARDS_REWARDS, "Card bills and due dates", null, null,
                100, KIND_COMPONENT, null, null, null, null, List.of(accountId), null, BuiltinAvailability.ALWAYS);
    }

    // ------------------------------------------------------------------ cards & rewards

    private Entry cardUtilisation() {
        return new Entry(CARD_UTILISATION, "Card utilisation",
                "How much of each credit card's limit you're using right now, from live balances. Banks and "
                        + "bureaus watch utilisation above 30%; this flags cards over it.",
                CATEGORY_CARDS_REWARDS, "Live limit usage", NEEDS_CARD, null,
                50, KIND_COMPONENT, null, null, null, "/accounts",
                List.of(ParamSpec.uuidRef("accountId", REF_CREDIT_CARD, false)), null,
                BuiltinAvailability.requiresAccountOfType(AccountType.credit_card, ADD_CARD));
    }

    /**
     * Milestone windows open today and not yet achieved (the view keeps the nearest per card), most
     * progressed first; an {@code accountId} narrows it to one card.
     */
    private Entry milestoneProgress() {
        ObjectNode def = mapper.createObjectNode();
        def.put("mode", "raw");
        def.putArray("columns").add("card").add("milestone").add("windowStart").add("windowEnd")
                .add("rewardType").add("basis").add("threshold").add("progress").add("progressPct").add("payoutValue");
        ArrayNode filters = def.putArray("filters");
        filter(filters, "achieved", "is", mapper.getNodeFactory().textNode("No"));
        def.putArray("sort").addObject().put("key", "progressPct").put("direction", "desc");
        return new Entry(MILESTONE_PROGRESS, "Milestone progress",
                "How close each card is to its next spend milestone, how many days are left, and how much you'd "
                        + "need to spend per day to reach it.",
                CATEGORY_CARDS_REWARDS, "Next spend milestones", NEEDS_CARD, VIEW_PROGRESS_LIST,
                50, KIND_TEMPLATE, ReportType.TABLE, "reward_milestones", def, "/rewards",
                List.of(ParamSpec.uuidRef("accountId", REF_CREDIT_CARD, false)),
                (definition, params) -> {
                    currentWindow(definition.withArray("filters"));
                    cardFilter(definition.withArray("filters"), params);
                },
                BuiltinAvailability.requiresAccountOfType(AccountType.credit_card, ADD_CARD));
    }

    /** Cap windows open today, the most used first; an {@code accountId} narrows it to one card. */
    private Entry capHeadroom() {
        ObjectNode def = mapper.createObjectNode();
        def.put("mode", "raw");
        def.putArray("columns").add("card").add("cap").add("window").add("windowStart").add("windowEnd")
                .add("cardholder").add("unit").add("capLimit").add("used").add("remaining").add("utilizationPct");
        def.putArray("filters");
        def.putArray("sort").addObject().put("key", "utilizationPct").put("direction", "desc");
        return new Entry(CAP_HEADROOM, "Cap headroom",
                "Reward caps that are nearly used up this cycle, so you know when to move a category of spend to "
                        + "another card.",
                CATEGORY_CARDS_REWARDS, "Reward caps this cycle", NEEDS_CARD, VIEW_CAP_LIST,
                50, KIND_TEMPLATE, ReportType.TABLE, "reward_caps", def, "/rewards",
                List.of(ParamSpec.uuidRef("accountId", REF_CREDIT_CARD, false)),
                (definition, params) -> {
                    currentWindow(definition.withArray("filters"));
                    cardFilter(definition.withArray("filters"), params);
                },
                BuiltinAvailability.requiresAccountOfType(AccountType.credit_card, ADD_CARD));
    }

    /** Rewards earned this Indian financial year, one row per card in rupees and points. */
    private Entry rewardsEarned() {
        ObjectNode def = mapper.createObjectNode();
        def.put("mode", "aggregated");
        def.putArray("rows").addObject().put("field", "card");
        def.putArray("columns");
        ArrayNode measures = def.putArray("measures");
        for (String measure : List.of("cashInr", "points", "pointsValueInr", "valueInr")) {
            measures.addObject().put("field", measure).put("aggregation", "sum");
        }
        filter(def.putArray("filters"), "effectiveDate", "current_fy", null);
        def.putArray("sort").addObject().put("key", "valueInr_sum").put("direction", "desc");
        return new Entry(REWARDS_EARNED, "Rewards earned",
                "Rewards earned this financial year, in rupees where the card has a point value and in points "
                        + "where it doesn't.",
                CATEGORY_CARDS_REWARDS, "This financial year", NEEDS_CARD, VIEW_REWARDS_FY,
                50, KIND_TEMPLATE, ReportType.TABLE, "reward_earnings", def, "/rewards", List.of(), null,
                BuiltinAvailability.requiresAccountOfType(AccountType.credit_card, ADD_CARD));
    }

    // ------------------------------------------------------------------ spending

    /**
     * Daily spend over the last {@code months} months ending today: debits only, excluded
     * transactions and transfer legs left out.
     */
    private Entry spendHeatmap() {
        ObjectNode def = mapper.createObjectNode();
        def.put("chartType", "bar");
        def.putObject("dimension").put("field", "date").put("granularity", "day");
        def.putNull("series");
        def.putObject("measure").put("field", "spend").put("aggregation", "sum");
        ArrayNode filters = def.putArray("filters");
        filter(filters, "type", "is", mapper.getNodeFactory().textNode("DEBIT"));
        filter(filters, "isExcluded", "is", mapper.getNodeFactory().booleanNode(false));
        filter(filters, "isTransferLeg", "is", mapper.getNodeFactory().booleanNode(false));
        ObjectNode window = mapper.createObjectNode().put("amount", HEATMAP_DEFAULT_MONTHS);
        filter(filters, "date", "last_x_months", window);
        ParamSpec months = ParamSpec.intParam("months", false,
                mapper.getNodeFactory().numberNode(HEATMAP_DEFAULT_MONTHS), 1, 12);
        return new Entry(SPEND_HEATMAP, "Spending calendar",
                "Daily spending as a calendar, darker on heavier days. Tap a day to see its transactions. "
                        + "Transfers between your own accounts are left out.",
                CATEGORY_SPENDING, "Daily spend", NEEDS_ACCOUNT, VIEW_HEATMAP,
                100, KIND_TEMPLATE, ReportType.CHART, "transactions", def, "/transactions", List.of(months),
                (definition, params) -> {
                    int amount = intParam(params, "months", HEATMAP_DEFAULT_MONTHS);
                    for (JsonNode f : definition.withArray("filters")) {
                        if ("date".equals(f.path("field").asText()) && f.isObject()) {
                            ((ObjectNode) f).putObject("value").put("amount", amount);
                        }
                    }
                },
                BuiltinAvailability.requiresAnyAccount(ADD_ACCOUNT));
    }

    // ------------------------------------------------------------------ investments

    private Entry portfolioSnapshot() {
        return new Entry(PORTFOLIO_SNAPSHOT, "Portfolio",
                "Current value, amount invested, unrealised gain and XIRR across all brokers, plus the change "
                        + "since the last evening price update.",
                CATEGORY_INVESTMENTS, "Value and day change", NEEDS_HOLDINGS, null,
                50, KIND_COMPONENT, null, null, null, "/investments", List.of(), null,
                BuiltinAvailability.requiresHoldings(ADD_HOLDINGS));
    }

    private Entry topMovers() {
        ParamSpec n = ParamSpec.intParam("n", false, mapper.getNodeFactory().numberNode(TOP_MOVERS_DEFAULT), 3, 10);
        return new Entry(TOP_MOVERS, "Top movers",
                "Your holdings that moved most since the previous evening price update. Mutual fund NAVs update "
                        + "a day late.",
                CATEGORY_INVESTMENTS, "Biggest moves today", NEEDS_HOLDINGS, null,
                50, KIND_COMPONENT, null, null, null, "/investments", List.of(n), null,
                BuiltinAvailability.requiresHoldings(ADD_HOLDINGS));
    }

    /** Open positions' current value by asset class. */
    private Entry allocation() {
        ObjectNode def = mapper.createObjectNode();
        def.put("chartType", "donut");
        def.putObject("dimension").put("field", "assetClass").putNull("granularity");
        def.putNull("series");
        def.putObject("measure").put("field", "currentValue").put("aggregation", "sum");
        filter(def.putArray("filters"), "isOpen", "is", mapper.getNodeFactory().booleanNode(true));
        return new Entry(ALLOCATION, "Allocation",
                "How your portfolio splits across equity, debt, hybrid, gold and international, at current market "
                        + "value.",
                CATEGORY_INVESTMENTS, "By asset class", NEEDS_HOLDINGS, VIEW_ALLOCATION,
                50, KIND_TEMPLATE, ReportType.CHART, "positions", def, "/investments", List.of(), null,
                BuiltinAvailability.requiresHoldings(ADD_HOLDINGS));
    }

    private Entry taxHarvest() {
        return new Entry(TAX_HARVEST, "Tax harvesting",
                "Capital gains booked this financial year, how much of the \u20b91.25L LTCG exemption is left, and "
                        + "which lots are worth selling \u2014 or waiting on \u2014 to use it. Not tax advice.",
                CATEGORY_INVESTMENTS, "Gains this FY", NEEDS_HOLDINGS, null,
                50, KIND_COMPONENT, null, null, null, "/investments", List.of(), null,
                BuiltinAvailability.requiresHoldings(ADD_HOLDINGS));
    }

    // ------------------------------------------------------------------ loans & lending

    private Entry loanPayoff() {
        return new Entry(LOAN_PAYOFF, "Loan payoff",
                "For each loan: what's left, how much principal you've repaid, the expected payoff date and the "
                        + "interest still to pay.",
                CATEGORY_LOANS_LENDING, "What's left to repay", "Needs a loan", null,
                50, KIND_COMPONENT, null, null, null, "/loans",
                List.of(ParamSpec.uuidRef("loanId", REF_LOAN, false)), null,
                BuiltinAvailability.requiresLoan("Add a loan first"));
    }

    private Entry lendingBalances() {
        return new Entry(LENDING_BALANCES, "Lending balances",
                "Who owes you and whom you owe, biggest first, with Settle up for each person.",
                CATEGORY_LOANS_LENDING, "Who owes whom", "Needs a lending entry", null,
                50, KIND_COMPONENT, null, null, null, "/loans/lendings", List.of(), null,
                BuiltinAvailability.requiresLending("Record a lending first"));
    }

    // ------------------------------------------------------------------ overview & shortcuts

    private Entry accountTile() {
        return new Entry(ACCOUNT_TILE, "Account",
                "One account's balance with a 30-day trend. Add one for each account you watch closely.",
                CATEGORY_OVERVIEW, "Balance and 30-day trend", NEEDS_ACCOUNT, null,
                25, KIND_COMPONENT, null, null, null, null,
                List.of(ParamSpec.uuidRef("accountId", REF_ACCOUNT, true)), null,
                BuiltinAvailability.requiresAnyAccount(ADD_ACCOUNT));
    }

    private Entry emergencyFund() {
        return new Entry(EMERGENCY_FUND, "Emergency fund",
                "How many months your bank and cash balances would cover at your usual monthly outflow (card "
                        + "bills and EMIs included). Accounts marked excluded are left out.",
                CATEGORY_OVERVIEW, "Months of outflow covered", NEEDS_ACCOUNT, null,
                50, KIND_COMPONENT, null, null, null, "/accounts", List.of(), null,
                BuiltinAvailability.requiresAnyAccount(ADD_ACCOUNT));
    }

    private Entry shortcuts() {
        ArrayNode defaults = mapper.createArrayNode();
        SHORTCUTS_DEFAULT.forEach(defaults::add);
        ParamSpec items = ParamSpec.stringList("items", false, defaults, SHORTCUTS_MAX_ITEMS, SHORTCUT_ITEM_PATTERN);
        return new Entry(SHORTCUTS, "Shortcuts",
                "Your own quick links to pages, accounts, reports and actions like Add transaction or Record "
                        + "lending. Pick and reorder them yourself.",
                CATEGORY_SHORTCUTS, null, null, null,
                25, KIND_COMPONENT, null, null, null, null, List.of(items), null, BuiltinAvailability.ALWAYS);
    }

    // ------------------------------------------------------------------ template helpers

    private static void filter(ArrayNode filters, String field, String operator, @Nullable JsonNode value) {
        ObjectNode f = filters.addObject();
        f.put("field", field);
        f.put("operator", operator);
        if (value != null) {
            f.set("value", value);
        }
    }

    /** Windows open today: started on or before today and ending on or after it. */
    private static void currentWindow(ArrayNode filters) {
        LocalDate today = AppTime.today();
        filter(filters, "windowStart", "before", filters.textNode(today.plusDays(1).toString()));
        filter(filters, "windowEnd", "after", filters.textNode(today.minusDays(1).toString()));
    }

    /** One card when {@code accountId} is given (computed datasources match the card's id). */
    private static void cardFilter(ArrayNode filters, @Nullable JsonNode params) {
        JsonNode accountId = params == null ? null : params.get("accountId");
        if (accountId != null && accountId.isTextual()) {
            filter(filters, "card", "is", filters.textNode(accountId.asText()));
        }
    }
}
