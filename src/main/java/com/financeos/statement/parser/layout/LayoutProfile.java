package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One statement layout, described declaratively: how to find its table header, which x-range holds which column
 * (relative to the header labels of the page, so a shifted page still reads), how the sign of an amount is printed,
 * what ends the table and which summary values sit where. Build with {@link #builder(String)}.
 */
public final class LayoutProfile {

    public enum Product { CARD, BANK }

    public enum Role { DATE, VALUE_DATE, NARRATION, REFERENCE, DEBIT, CREDIT, AMOUNT, MARKER, BALANCE, IGNORE }

    public enum Sign { SEPARATE_COLUMNS, SUFFIX, PREFIX, MINUS_IS_CREDIT, BALANCE_DELTA }

    public enum Direction { DEBIT, CREDIT }

    public enum Order { ASC, DESC, AUTO }

    public enum RowShape { SINGLE_LINE, CUSTOM }

    public enum EndScope { PAGE, FOR_GOOD }

    public enum Position { RIGHT_ON_LINE, BELOW_BY_X, NTH_AMOUNT_BELOW, CAPTURE }

    public enum ValueType { AMOUNT, DATE, DATE_RANGE, MASKED_NUMBER, INTEGER, TEXT }

    public enum MetaTarget {
        PERIOD, PERIOD_START, PERIOD_END, STATEMENT_DATE, ACCOUNT_NUMBER, IFSC, HOLDER_NAME, OPENING_BALANCE, CLOSING_BALANCE
    }

    /** A header label: its name (what columns refer to) and the regex that finds it in the header line. */
    public record HeaderLabel(String name, Pattern regex) {
    }

    /** One side of a column: the x0 of the named header label on the page plus dx, or (label null) an absolute x. */
    public record Edge(String label, double dx) {
    }

    public record Column(Role role, Edge from, Edge to) {
    }

    public record EndMarker(Pattern regex, EndScope scope) {
    }

    /** SUFFIX rule: the tokens that mean credit and debit, and the direction of an amount that carries neither. */
    /**
     * PREFIX rule: the marker is the token right before the amount (optionally with a currency glyph token between).
     * credit / debit are full-match regexes (debit may be null: debits print nothing).
     */
    public record PrefixRule(Pattern credit, Pattern debit, Direction defaultDirection) {
    }

    /** A header geometry requirement: the x0 of the named header label must lie in [minX, maxX]. */
    public record LabelAt(String label, double minX, double maxX) {
    }

    /** Edge label meaning "x0 of the leftmost date token of the page" (continuation columns only). */
    public static final String FIRST_DATE = "$firstDate";

    public record SuffixRule(String creditToken, String debitToken, Direction defaultDirection) {
    }

    /**
     * One summary value. The target is a legacy summary key (legacyKey) or a meta field (metaTarget); label is found in
     * the text of a line (first line that matches and yields a value wins); position says where the value sits.
     * creditIsNegative: a Cr-marked or minus value is returned negative (dues); creditIsZero: a Cr-marked value means
     * nothing owed and is returned as 0. Position CAPTURE: group 1 of {@code capture} run over the label line's text
     * (and the next captureLines lines), parsed by the type; compact removes inner whitespace of a MASKED_NUMBER.
     */
    public record Summary(String legacyKey, MetaTarget metaTarget, Pattern label, Position position, ValueType type,
                          int n, int maxLines, double xTolerance, Set<Integer> pages, boolean creditIsNegative,
                          boolean creditIsZero, Pattern capture, int captureLines, boolean compact, int maxWords) {
    }

    /** What a custom row strategy returns for one row of the table. */
    public record TableRow(Line line, LocalDate date, String description, BigDecimal debit, BigDecimal credit,
                           BigDecimal amount, boolean amountNegativeMarked, String marker, BigDecimal balance,
                           String cardLast4) {
    }

    /** Escape hatch for layouts whose rows are not one line (blocks): turns a page's table lines into rows. */
    public interface RowStrategy {
        List<TableRow> read(List<Line> tableLines, Map<String, Double> labelX0, DateContext dates);
    }

    /** Date parsing offered to a custom row strategy. */
    public interface DateContext {
        LocalDate parse(String text);
    }

    private final String id;
    private final String issuerId;
    private final Product product;
    private final String bank;
    private final List<HeaderLabel> header;
    private final int headerLines;
    private final List<Pattern> headerRejects;
    private final List<Pattern> requires;
    private final List<Column> columns;
    private final List<String> dateFormats;
    private final Sign sign;
    private final SuffixRule suffix;
    private final PrefixRule prefix;
    private final Pattern cardLast4From;
    private final boolean cardLast4FromAccountNumber;
    private final List<Column> continuationColumns;
    private final List<LabelAt> labelGeometry;
    private final boolean accountNumberFromSectionHeader;
    private final RowShape rowShape;
    private final RowStrategy rowStrategy;
    private final double continuationBelow;
    private final double continuationAbove;
    private final List<EndMarker> endMarkers;
    private final List<Pattern> skipLines;
    private final Pattern sectionHeader;
    private final boolean continuesWithoutHeader;
    private final Set<String> notPrinted;
    private final Order order;
    private final List<Summary> summary;

    private LayoutProfile(Builder b) {
        this.id = Objects.requireNonNull(b.id);
        this.issuerId = b.issuerId;
        this.product = Objects.requireNonNull(b.product, "product");
        this.bank = b.bank;
        this.header = List.copyOf(b.header);
        this.headerLines = Math.max(1, b.headerLines);
        this.headerRejects = List.copyOf(b.headerRejects);
        this.requires = List.copyOf(b.requires);
        this.columns = List.copyOf(b.columns);
        this.dateFormats = List.copyOf(b.dateFormats);
        this.sign = Objects.requireNonNull(b.sign, "sign");
        this.suffix = b.suffix;
        this.prefix = b.prefix;
        this.cardLast4From = b.cardLast4From;
        this.cardLast4FromAccountNumber = b.cardLast4FromAccountNumber;
        this.continuationColumns = List.copyOf(b.continuationColumns);
        this.labelGeometry = List.copyOf(b.labelGeometry);
        this.accountNumberFromSectionHeader = b.accountNumberFromSectionHeader;
        this.rowShape = b.rowStrategy != null ? RowShape.CUSTOM : RowShape.SINGLE_LINE;
        this.rowStrategy = b.rowStrategy;
        this.continuationBelow = b.continuationBelow;
        this.continuationAbove = b.continuationAbove;
        this.endMarkers = List.copyOf(b.endMarkers);
        this.skipLines = List.copyOf(b.skipLines);
        this.sectionHeader = b.sectionHeader;
        this.continuesWithoutHeader = b.continuesWithoutHeader;
        this.notPrinted = Set.copyOf(b.notPrinted);
        this.order = b.order;
        this.summary = List.copyOf(b.summary);
        if (header.isEmpty()) {
            throw new IllegalStateException("a profile needs a header");
        }
        if (sign == Sign.PREFIX && prefix == null) {
            throw new IllegalStateException("PREFIX sign rule needs prefix tokens");
        }
        if (!continuationColumns.isEmpty()) {
            if (continuationColumns.stream().noneMatch(c -> c.role() == Role.DATE)) {
                throw new IllegalStateException("continuation columns need a DATE column");
            }
            for (Column c : continuationColumns) {
                for (Edge e : List.of(c.from(), c.to())) {
                    if (e.label() != null && !FIRST_DATE.equals(e.label())) {
                        throw new IllegalStateException("continuation columns are absolute or first-date relative");
                    }
                }
            }
        }
        if (sign == Sign.SUFFIX && suffix == null) {
            throw new IllegalStateException("SUFFIX sign rule needs suffix tokens");
        }
        boolean hasAmountColumn = columns.stream().anyMatch(c -> c.role() == Role.AMOUNT);
        boolean hasSplit = columns.stream().anyMatch(c -> c.role() == Role.DEBIT)
                && columns.stream().anyMatch(c -> c.role() == Role.CREDIT);
        if (sign == Sign.SEPARATE_COLUMNS ? !hasSplit : !hasAmountColumn) {
            throw new IllegalStateException("sign rule " + sign + " does not match the amount columns");
        }
        if (sign == Sign.BALANCE_DELTA && columns.stream().noneMatch(c -> c.role() == Role.BALANCE)) {
            throw new IllegalStateException("BALANCE_DELTA needs a BALANCE column");
        }
        if (product == Product.CARD) {
            Set<String> accounted = new LinkedHashSet<>(notPrinted);
            summary.forEach(sm -> accounted.add(sm.legacyKey()));
            List<String> missing = new ArrayList<>(CardFieldKeys.KEYS);
            missing.removeAll(accounted);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("card profile " + id + " neither declares nor marks notPrinted: " + missing);
            }
        }
        Set<String> names = new LinkedHashSet<>();
        header.forEach(h -> names.add(h.name()));
        for (LabelAt g : labelGeometry) {
            if (!names.contains(g.label())) {
                throw new IllegalStateException("requiresLabelAt refers to unknown header label " + g.label());
            }
        }
        for (Column c : columns) {
            for (Edge e : List.of(c.from(), c.to())) {
                if (e.label() != null && !names.contains(e.label())) {
                    throw new IllegalStateException("column refers to unknown header label " + e.label());
                }
            }
        }
    }

    public String id() { return id; }
    public String issuerId() { return issuerId; }
    public Product product() { return product; }
    public String bank() { return bank; }
    public List<HeaderLabel> header() { return header; }
    public List<Pattern> headerRejects() { return headerRejects; }
    public int headerLines() { return headerLines; }
    public List<Pattern> requires() { return requires; }
    public List<Column> columns() { return columns; }
    public List<String> dateFormats() { return dateFormats; }
    public Sign sign() { return sign; }
    public SuffixRule suffix() { return suffix; }
    public PrefixRule prefix() { return prefix; }
    public Pattern cardLast4From() { return cardLast4From; }
    public boolean cardLast4FromAccountNumber() { return cardLast4FromAccountNumber; }
    public List<Column> continuationColumns() { return continuationColumns; }
    public List<LabelAt> labelGeometry() { return labelGeometry; }
    public boolean accountNumberFromSectionHeader() { return accountNumberFromSectionHeader; }
    public RowShape rowShape() { return rowShape; }
    public RowStrategy rowStrategy() { return rowStrategy; }
    public double continuationBelow() { return continuationBelow; }
    public double continuationAbove() { return continuationAbove; }
    public List<EndMarker> endMarkers() { return endMarkers; }
    public List<Pattern> skipLines() { return skipLines; }
    public Pattern sectionHeader() { return sectionHeader; }
    public Order order() { return order; }
    public boolean continuesWithoutHeader() { return continuesWithoutHeader; }
    public Set<String> notPrinted() { return notPrinted; }
    public List<Summary> summary() { return summary; }

    public boolean hasBalanceColumn() {
        return columns.stream().anyMatch(c -> c.role() == Role.BALANCE);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Edge helpers for the builder: from(label, dx) / to(label, dx) relative, fromX / toX absolute. */
    public static Edge at(String label, double dx) {
        return new Edge(label, dx);
    }

    public static Edge absolute(double x) {
        return new Edge(null, x);
    }

    public static final class Builder {
        private final String id;
        private String issuerId;
        private Product product;
        private String bank;
        private final List<HeaderLabel> header = new ArrayList<>();
        private int headerLines = 1;
        private final List<Pattern> headerRejects = new ArrayList<>();
        private final List<Pattern> requires = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();
        private List<String> dateFormats = List.of("dd/MM/yyyy");
        private Sign sign;
        private SuffixRule suffix;
        private PrefixRule prefix;
        private Pattern cardLast4From;
        private boolean cardLast4FromAccountNumber;
        private final List<Column> continuationColumns = new ArrayList<>();
        private final List<LabelAt> labelGeometry = new ArrayList<>();
        private boolean accountNumberFromSectionHeader;
        private RowStrategy rowStrategy;
        private double continuationBelow;
        private double continuationAbove;
        private final List<EndMarker> endMarkers = new ArrayList<>();
        private final List<Pattern> skipLines = new ArrayList<>();
        private Pattern sectionHeader;
        private Order order = Order.ASC;
        private boolean continuesWithoutHeader = true;
        private final Set<String> notPrinted = new LinkedHashSet<>();
        private final List<Summary> summary = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder issuer(String issuerId) { this.issuerId = issuerId; return this; }

        public Builder product(Product product) { this.product = product; return this; }

        /** The bank name to report in the statement meta (v1 guesses it from the page text). */
        public Builder bank(String bank) { this.bank = bank; return this; }

        /** A header label (regex, case-insensitive) that must appear after the previous one on the same line. */
        public Builder headerLabel(String name, String regex) {
            header.add(new HeaderLabel(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE)));
            return this;
        }

        /**
         * The header may span up to n consecutive lines (default 1): its labels are matched in order across them, each
         * anchoring its column by its own x0 on the line it sits; the table starts after the last header line.
         */
        public Builder headerLines(int n) { this.headerLines = n; return this; }

        /** A regex that, found in a would-be header line, rejects it (a wider table of another layout). */
        public Builder headerRejects(String regex) {
            headerRejects.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            return this;
        }

        /** A regex that must be found in the document's text for the profile to match (tells look-alike layouts apart). */
        public Builder requires(String regex) {
            requires.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            return this;
        }

        /** A column that spans from one header label's x0 + dx0 to another's (or the same's) x0 + dx1. */
        public Builder column(Role role, Edge from, Edge to) {
            columns.add(new Column(role, from, to));
            return this;
        }

        /** A column placed relative to one header label: [x0 + dx0, x0 + dx1). */
        public Builder fromLabel(Role role, String label, double dx0, double dx1) {
            return column(role, at(label, dx0), at(label, dx1));
        }

        /** A column at fixed x positions. */
        public Builder absoluteColumn(Role role, double x0, double x1) {
            return column(role, absolute(x0), absolute(x1));
        }

        /** Java date patterns, tried in order; a pattern without a year takes it from the statement period. */
        public Builder dateFormats(String... patterns) { this.dateFormats = List.of(patterns); return this; }

        public Builder sign(Sign sign) { this.sign = sign; return this; }

        /**
         * The sign marker is printed BEFORE the amount ("+" for credits): the token right before the amount, or before a
         * currency glyph that precedes it. Tokens are full-match regexes (case-insensitive); debitRegex may be null when
         * debits print nothing. Same adjacency and stray-token safety as SUFFIX; a marker-like word inside the
         * NARRATION range is narration.
         */
        public Builder prefix(String creditRegex, String debitRegex, Direction defaultDirection) {
            this.sign = Sign.PREFIX;
            this.prefix = new PrefixRule(Pattern.compile(creditRegex, Pattern.CASE_INSENSITIVE),
                    debitRegex == null ? null : Pattern.compile(debitRegex, Pattern.CASE_INSENSITIVE), defaultDirection);
            return this;
        }

        /**
         * Header geometry a layout variant is told apart by: the x0 of the named header label must lie in [minX, maxX]
         * for the header to count. Variants of one family that arrange the same labels differently are separate profiles,
         * each with its own relative columns and its own requiresLabelAt.
         */
        public Builder requiresLabelAt(String label, double minX, double maxX) {
            labelGeometry.add(new LabelAt(label, minX, maxX));
            return this;
        }

        /**
         * The first section header's group 1 is the full card number: it fills the account number when no ACCOUNT_NUMBER
         * summary found one, and each row's card last 4 becomes the last 4 digits of that group.
         */
        public Builder accountNumberFromSectionHeader() { this.accountNumberFromSectionHeader = true; return this; }

        /** Rows with no section header above them take the last 4 digits of the ACCOUNT_NUMBER summary value. */
        public Builder cardLast4FromAccountNumber() { this.cardLast4FromAccountNumber = true; return this; }

        /** Rows with no section header above them take the last 4 digits of group 1 over the lines before the table. */
        public Builder cardLast4From(String regex) {
            this.cardLast4From = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            if (cardLast4From.matcher("").groupCount() < 1) {
                throw new IllegalArgumentException("cardLast4From needs a group 1");
            }
            return this;
        }

        /** A column of header-less continuation pages (see continuationFromX / continuationFromFirstDate). */
        public Builder continuationColumn(Role role, Edge from, Edge to) {
            continuationColumns.add(new Column(role, from, to));
            return this;
        }

        /**
         * Column of header-less continuation pages at ABSOLUTE x-ranges. Use it when the template prints those pages
         * with its own fixed geometry (roles shift by different amounts, so no single offset describes them).
         */
        public Builder continuationFromX(Role role, double x0, double x1) {
            return continuationColumn(role, absolute(x0), absolute(x1));
        }

        /** Column of continuation pages relative to the x0 of the leftmost date token of that page (a uniform page shift). */
        public Builder continuationFromFirstDate(Role role, double dx0, double dx1) {
            return continuationColumn(role, at(FIRST_DATE, dx0), at(FIRST_DATE, dx1));
        }

        public Builder suffix(String creditToken, String debitToken, Direction defaultDirection) {
            this.sign = Sign.SUFFIX;
            this.suffix = new SuffixRule(creditToken, debitToken, defaultDirection);
            return this;
        }

        /** Narration lines without a date: within maxBelow of the row above or maxAbove of the row below (y units). */
        public Builder continuation(double maxBelow, double maxAbove) {
            this.continuationBelow = maxBelow;
            this.continuationAbove = maxAbove;
            return this;
        }

        public Builder rowStrategy(RowStrategy strategy) { this.rowStrategy = strategy; return this; }

        public Builder endMarker(String regex, EndScope scope) {
            endMarkers.add(new EndMarker(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), scope));
            return this;
        }

        public Builder skipLine(String regex) {
            skipLines.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            return this;
        }

        /** A line that names the card the following rows belong to; group 1 is its last four digits. */
        public Builder sectionHeader(String regex) {
            this.sectionHeader = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            return this;
        }

        public Builder order(Order order) { this.order = order; return this; }

        /** A page without the header continues the table with the previous page's geometry (default true). */
        public Builder continuesWithoutHeader(boolean continues) { this.continuesWithoutHeader = continues; return this; }

        /** A card summary key this layout does not print (every key of {@link CardFieldKeys} is declared or marked). */
        public Builder notPrinted(String legacyKey) { notPrinted.add(legacyKey); return this; }

        /** A legacy summary key (e.g. total_amount_due). */
        public SummaryBuilder summary(String legacyKey, String labelRegex) {
            return new SummaryBuilder(this, legacyKey, null, labelRegex);
        }

        /** A meta field (period, statement date, account number, opening or closing balance). */
        public SummaryBuilder summary(MetaTarget target, String labelRegex) {
            return new SummaryBuilder(this, null, target, labelRegex);
        }

        public LayoutProfile build() {
            return new LayoutProfile(this);
        }
    }

    /** Fluent definition of one summary value; {@link #done()} returns to the profile builder. */
    public static final class SummaryBuilder {
        private final Builder parent;
        private final String legacyKey;
        private final MetaTarget metaTarget;
        private final Pattern label;
        private Position position = Position.BELOW_BY_X;
        private ValueType type = ValueType.AMOUNT;
        private int n = 1;
        private int maxLines = 3;
        private double xTolerance = 10;
        private final Set<Integer> pages = new LinkedHashSet<>();
        private boolean creditIsNegative;
        private boolean creditIsZero;
        private Pattern capture;
        private int captureLines;
        private boolean compact;
        private int maxWords = 1;

        private SummaryBuilder(Builder parent, String legacyKey, MetaTarget metaTarget, String labelRegex) {
            this.parent = parent;
            this.legacyKey = legacyKey;
            this.metaTarget = metaTarget;
            this.label = Pattern.compile(labelRegex, Pattern.CASE_INSENSITIVE);
        }

        public SummaryBuilder rightOnLine() { this.position = Position.RIGHT_ON_LINE; return this; }

        /** The value is on a following line whose words overlap the label's x-range (within xTolerance). */
        public SummaryBuilder below() { this.position = Position.BELOW_BY_X; return this; }

        /** Equation row: the value is the nth (1-based) amount of the next line that carries amounts. */
        public SummaryBuilder nthAmountBelow(int n) { this.position = Position.NTH_AMOUNT_BELOW; this.n = n; return this; }

        /**
         * The value is group 1 of this regex run over the label line's text (words joined by single spaces); the group
         * is parsed by the declared type. No match, or a group the type cannot parse, gives no value. Works with every type
         * (DATE_RANGE: two groups, or two dates inside group 1).
         */
        public SummaryBuilder capture(String regex) {
            this.position = Position.CAPTURE;
            this.capture = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            if (capture.matcher("").groupCount() < 1) {
                throw new IllegalArgumentException("capture needs a group 1");
            }
            return this;
        }

        /** The capture regex is also tried on each of the next n lines of the page (default 0). */
        public SummaryBuilder captureLines(int n) { this.captureLines = n; return this; }

        /** TEXT with RIGHT_ON_LINE / BELOW_BY_X: take up to n words (default 1; lone punctuation words are skipped). */
        public SummaryBuilder maxWords(int n) { this.maxWords = n; return this; }

        /** MASKED_NUMBER: remove the whitespace inside the captured value (default: kept as captured). */
        public SummaryBuilder compact() { this.compact = true; return this; }

        public SummaryBuilder type(ValueType type) { this.type = type; return this; }

        public SummaryBuilder maxLines(int maxLines) { this.maxLines = maxLines; return this; }

        public SummaryBuilder xTolerance(double xTolerance) { this.xTolerance = xTolerance; return this; }

        /** Only these pages (0 = first page); none given = every page. */
        public SummaryBuilder pages(Integer... pages) { this.pages.addAll(List.of(pages)); return this; }

        public SummaryBuilder creditIsNegative() { this.creditIsNegative = true; return this; }

        public SummaryBuilder creditIsZero() { this.creditIsZero = true; return this; }

        public Builder done() {
            // only target / type pairs the reader can store: a wrongly typed value can never reach a throwing cast
            if (metaTarget != null) {
                java.util.Set<ValueType> ok = switch (metaTarget) {
                    case PERIOD -> java.util.Set.of(ValueType.DATE_RANGE);
                    case PERIOD_START, PERIOD_END, STATEMENT_DATE -> java.util.Set.of(ValueType.DATE);
                    case ACCOUNT_NUMBER -> java.util.Set.of(ValueType.MASKED_NUMBER, ValueType.TEXT);
                    case IFSC, HOLDER_NAME -> java.util.Set.of(ValueType.TEXT);
                    case OPENING_BALANCE, CLOSING_BALANCE -> java.util.Set.of(ValueType.AMOUNT);
                };
                if (!ok.contains(type)) {
                    throw new IllegalStateException(metaTarget + " cannot take a " + type + " value (allowed: " + ok + ")");
                }
            } else if ("payment_due_date".equals(legacyKey) ? type != ValueType.DATE
                    : (type != ValueType.AMOUNT && type != ValueType.INTEGER)) {
                throw new IllegalStateException("summary key " + legacyKey + " cannot take a " + type + " value");
            }
            if (position == Position.NTH_AMOUNT_BELOW && type != ValueType.AMOUNT) {
                throw new IllegalStateException("nthAmountBelow reads amounts only");
            }
            if ((metaTarget == MetaTarget.PERIOD_START || metaTarget == MetaTarget.PERIOD_END) && type != ValueType.DATE) {
                throw new IllegalStateException(metaTarget + " takes a single DATE");
            }
            parent.summary.add(new Summary(legacyKey, metaTarget, label, position, type, n, maxLines, xTolerance,
                    Set.copyOf(pages), creditIsNegative, creditIsZero, capture, captureLines, compact, maxWords));
            return parent;
        }
    }
}
