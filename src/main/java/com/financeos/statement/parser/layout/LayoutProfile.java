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

    public enum Sign { SEPARATE_COLUMNS, SUFFIX, MINUS_IS_CREDIT, BALANCE_DELTA }

    public enum Direction { DEBIT, CREDIT }

    public enum Order { ASC, DESC, AUTO }

    public enum RowShape { SINGLE_LINE, CUSTOM }

    public enum EndScope { PAGE, FOR_GOOD }

    public enum Position { RIGHT_ON_LINE, BELOW_BY_X, NTH_AMOUNT_BELOW }

    public enum ValueType { AMOUNT, DATE, DATE_RANGE, MASKED_NUMBER, INTEGER }

    public enum MetaTarget { PERIOD, STATEMENT_DATE, ACCOUNT_NUMBER, OPENING_BALANCE, CLOSING_BALANCE }

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
    public record SuffixRule(String creditToken, String debitToken, Direction defaultDirection) {
    }

    /**
     * One summary value. The target is a legacy summary key (legacyKey) or a meta field (metaTarget); label is found in
     * the text of a line (first line that matches and yields a value wins); position says where the value sits.
     * creditIsNegative: a Cr-marked or minus value is returned negative (dues); creditIsZero: a Cr-marked value means
     * nothing owed and is returned as 0.
     */
    public record Summary(String legacyKey, MetaTarget metaTarget, Pattern label, Position position, ValueType type,
                          int n, int maxLines, double xTolerance, Set<Integer> pages, boolean creditIsNegative,
                          boolean creditIsZero) {
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
    private final List<Pattern> headerRejects;
    private final List<Pattern> requires;
    private final List<Column> columns;
    private final List<String> dateFormats;
    private final Sign sign;
    private final SuffixRule suffix;
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
        this.headerRejects = List.copyOf(b.headerRejects);
        this.requires = List.copyOf(b.requires);
        this.columns = List.copyOf(b.columns);
        this.dateFormats = List.copyOf(b.dateFormats);
        this.sign = Objects.requireNonNull(b.sign, "sign");
        this.suffix = b.suffix;
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
    public List<Pattern> requires() { return requires; }
    public List<Column> columns() { return columns; }
    public List<String> dateFormats() { return dateFormats; }
    public Sign sign() { return sign; }
    public SuffixRule suffix() { return suffix; }
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
        private final List<Pattern> headerRejects = new ArrayList<>();
        private final List<Pattern> requires = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();
        private List<String> dateFormats = List.of("dd/MM/yyyy");
        private Sign sign;
        private SuffixRule suffix;
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

        public SummaryBuilder type(ValueType type) { this.type = type; return this; }

        public SummaryBuilder maxLines(int maxLines) { this.maxLines = maxLines; return this; }

        public SummaryBuilder xTolerance(double xTolerance) { this.xTolerance = xTolerance; return this; }

        /** Only these pages (0 = first page); none given = every page. */
        public SummaryBuilder pages(Integer... pages) { this.pages.addAll(List.of(pages)); return this; }

        public SummaryBuilder creditIsNegative() { this.creditIsNegative = true; return this; }

        public SummaryBuilder creditIsZero() { this.creditIsZero = true; return this; }

        public Builder done() {
            parent.summary.add(new Summary(legacyKey, metaTarget, label, position, type, n, maxLines, xTolerance,
                    Set.copyOf(pages), creditIsNegative, creditIsZero));
            return parent;
        }
    }
}
