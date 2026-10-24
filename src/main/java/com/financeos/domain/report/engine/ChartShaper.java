package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartOrder;
import com.financeos.domain.report.definition.ChartType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shaping both chart executors share: top-N with an "Other" fold, category order, the
 * previous-period alignment and the breakdown rows. It works on the executors' grouped results
 * (group counts are small), so none of this is SQL.
 *
 * <p>A category's rank is the absolute value of its total across series (value_desc / value_asc
 * and the top-N cut use it; ties keep dimension order). The folded "Other" aggregates its
 * members' cells like the measure does: SUM/COUNT add up, AVG is weighted by each cell's
 * measure count, MIN/MAX take the extreme.
 */
final class ChartShaper {

    static final String OTHER_LABEL = "Other";

    private ChartShaper() {
    }

    /**
     * One grouped cell (a category, or a category within a series).
     *
     * @param value        the measure's aggregate
     * @param count        source rows in the cell
     * @param measureCount source rows with a measure value (the weight of {@code avg})
     * @param avg          plain average of the measure over the cell; null when it has no values
     */
    record Cell(BigDecimal value, long count, long measureCount, BigDecimal avg) {
    }

    /**
     * A grouped result. {@code categories} are in dimension order; {@code cells} maps series name
     * to category to cell; {@code ranges} maps a date-bucket category to its inclusive dates (null
     * when the dimension is not a date).
     */
    record Grouped(List<String> categories, List<String> seriesNames, Map<String, Map<String, Cell>> cells,
                   Map<String, ChartData.CategoryRange> ranges) {
    }

    /** One sub-category of a category: the sub-dimension's label and its cell. */
    record SubCell(String label, BigDecimal value, long count) {
    }

    /** The shaped pieces the executors put into {@link ChartData}. */
    record Shaped(List<String> categories, List<ChartData.Series> series, List<ChartData.Series> previousSeries,
                  ChartData.Other other, ChartData.Breakdown breakdown, List<ChartData.CategoryRange> categoryRanges) {
    }

    /**
     * @param previous the previous period's grouped result; null when none resolves
     * @param subCells every (category, sub-category) cell of the current period, by category; null
     *                 unless the chart is a breakdown with a sub-dimension
     */
    static Shaped shape(ChartDefinition def, Aggregation agg, Grouped current, Grouped previous,
                        Map<String, List<SubCell>> subCells) {
        List<String> cats = current.categories();
        int n = cats.size();
        BigDecimal missing = isAdditive(agg) ? BigDecimal.ZERO : null;

        BigDecimal[] rank = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            rank[i] = total(current, cats.get(i), current.seriesNames()).abs();
        }

        // Top N: the biggest by rank survive; the rest fold into Other or are dropped.
        List<Integer> kept = new ArrayList<>();
        List<Integer> folded = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            kept.add(i);
        }
        Integer limit = def.limit();
        if (limit != null && n > limit) {
            List<Integer> ranked = new ArrayList<>(kept);
            ranked.sort(Comparator.comparing((Integer i) -> rank[i]).reversed()); // stable: ties keep dimension order
            Set<Integer> top = new HashSet<>(ranked.subList(0, limit));
            kept.clear();
            for (int i = 0; i < n; i++) {
                (top.contains(i) ? kept : folded).add(i);
            }
        }

        ChartOrder order = def.order() != null ? def.order()
                : def.chartType() == ChartType.BREAKDOWN ? ChartOrder.VALUE_DESC : ChartOrder.DIMENSION;
        if (order == ChartOrder.VALUE_DESC) {
            kept.sort(Comparator.comparing((Integer i) -> rank[i]).reversed());
        } else if (order == ChartOrder.VALUE_ASC) {
            kept.sort(Comparator.comparing((Integer i) -> rank[i]));
        }

        boolean fold = Boolean.TRUE.equals(def.other()) && !folded.isEmpty();
        List<String> keptLabels = new ArrayList<>();
        for (int i : kept) {
            keptLabels.add(cats.get(i));
        }
        List<String> foldedLabels = new ArrayList<>();
        for (int i : folded) {
            foldedLabels.add(cats.get(i));
        }

        List<String> categories = new ArrayList<>(keptLabels);
        ChartData.Other other = null;
        if (fold) {
            other = new ChartData.Other(categories.size(), folded.size());
            categories.add(OTHER_LABEL);
        }

        List<ChartData.Series> series = new ArrayList<>();
        for (String name : current.seriesNames()) {
            Map<String, Cell> byCat = current.cells().getOrDefault(name, Map.of());
            List<BigDecimal> data = new ArrayList<>();
            for (String c : keptLabels) {
                Cell cell = byCat.get(c);
                data.add(cell != null && cell.value() != null ? cell.value() : missing);
            }
            if (fold) {
                data.add(foldValue(agg, cellsOf(byCat, foldedLabels), missing));
            }
            series.add(new ChartData.Series(name, data));
        }

        // Previous period: aligned to the kept categories by label; Other = everything not kept.
        List<ChartData.Series> previousSeries = null;
        List<String> previousNotKept = List.of();
        if (previous != null) {
            Set<String> keptSet = new HashSet<>(keptLabels);
            previousNotKept = previous.categories().stream().filter(c -> !keptSet.contains(c)).toList();
            previousSeries = new ArrayList<>();
            for (String name : current.seriesNames()) {
                Map<String, Cell> byCat = previous.cells().getOrDefault(name, Map.of());
                List<BigDecimal> data = new ArrayList<>();
                for (String c : keptLabels) {
                    Cell cell = byCat.get(c);
                    data.add(cell != null && cell.value() != null ? cell.value() : missing);
                }
                if (fold) {
                    data.add(foldValue(agg, cellsOf(byCat, previousNotKept), missing));
                }
                previousSeries.add(new ChartData.Series(name, data));
            }
        }

        ChartData.Breakdown breakdown = null;
        if (def.chartType() == ChartType.BREAKDOWN) {
            breakdown = breakdown(def, agg, current, previous, keptLabels, foldedLabels, previousNotKept, fold, subCells);
        }

        List<ChartData.CategoryRange> ranges = null;
        if (current.ranges() != null) {
            ranges = new ArrayList<>();
            for (String c : keptLabels) {
                ranges.add(current.ranges().get(c));
            }
            if (fold) {
                ranges.add(null);
            }
        }
        return new Shaped(categories, series, previousSeries, other, breakdown, ranges);
    }

    private static ChartData.Breakdown breakdown(ChartDefinition def, Aggregation agg, Grouped current, Grouped previous,
            List<String> keptLabels, List<String> foldedLabels, List<String> previousNotKept, boolean fold,
            Map<String, List<SubCell>> subCells) {
        int subLimit = def.subLimit() != null ? def.subLimit() : ChartDefinition.DEFAULT_SUB_LIMIT;
        List<ChartData.BreakdownRow> rows = new ArrayList<>();
        for (String c : keptLabels) {
            List<ChartData.SubRow> sub = new ArrayList<>();
            List<SubCell> all = subCells == null ? null : subCells.get(c);
            if (all != null) {
                List<SubCell> sorted = new ArrayList<>(all);
                // Equal values tie-break on label so the SQL and in-memory paths pick the same sub-rows.
                sorted.sort(Comparator.comparing((SubCell s) -> s.value() == null ? BigDecimal.ZERO : s.value().abs())
                        .reversed().thenComparing(SubCell::label));
                for (SubCell s : sorted.subList(0, Math.min(subLimit, sorted.size()))) {
                    sub.add(new ChartData.SubRow(s.label(), s.value(), s.count()));
                }
            }
            List<Cell> cells = categoryCells(current, List.of(c));
            rows.add(new ChartData.BreakdownRow(countOf(cells), averageOf(cells),
                    previous == null ? null : countOf(categoryCells(previous, List.of(c))), sub));
        }
        if (fold) {
            List<Cell> cells = categoryCells(current, foldedLabels);
            rows.add(new ChartData.BreakdownRow(countOf(cells), averageOf(cells),
                    previous == null ? null : countOf(categoryCells(previous, previousNotKept)), List.of()));
        }
        BigDecimal total = null;
        BigDecimal previousTotal = null;
        if (isAdditive(agg)) {
            total = BigDecimal.ZERO;
            for (String c : current.categories()) {
                total = total.add(total(current, c, current.seriesNames()));
            }
        }
        if (previous != null && isAdditive(agg)) {
            previousTotal = BigDecimal.ZERO;
            for (String c : previous.categories()) {
                previousTotal = previousTotal.add(total(previous, c, current.seriesNames()));
            }
        }
        return new ChartData.Breakdown(rows, total, previousTotal);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isAdditive(Aggregation agg) {
        return agg == Aggregation.SUM || agg == Aggregation.COUNT;
    }

    private static BigDecimal total(Grouped g, String category, List<String> seriesNames) {
        BigDecimal t = BigDecimal.ZERO;
        for (String s : seriesNames) {
            Cell cell = g.cells().getOrDefault(s, Map.of()).get(category);
            if (cell != null && cell.value() != null) {
                t = t.add(cell.value());
            }
        }
        return t;
    }

    private static List<Cell> cellsOf(Map<String, Cell> byCategory, List<String> categories) {
        List<Cell> out = new ArrayList<>();
        for (String c : categories) {
            Cell cell = byCategory.get(c);
            if (cell != null) {
                out.add(cell);
            }
        }
        return out;
    }

    /** Every series' cell of the given categories. */
    private static List<Cell> categoryCells(Grouped g, List<String> categories) {
        List<Cell> out = new ArrayList<>();
        for (String s : g.seriesNames()) {
            out.addAll(cellsOf(g.cells().getOrDefault(s, Map.of()), categories));
        }
        return out;
    }

    private static long countOf(List<Cell> cells) {
        long n = 0;
        for (Cell c : cells) {
            n += c.count();
        }
        return n;
    }

    /** The measure's plain average over the cells, weighted by their measure counts. */
    private static BigDecimal averageOf(List<Cell> cells) {
        BigDecimal sum = BigDecimal.ZERO;
        long weight = 0;
        for (Cell c : cells) {
            if (c.avg() != null && c.measureCount() > 0) {
                sum = sum.add(c.avg().multiply(BigDecimal.valueOf(c.measureCount())));
                weight += c.measureCount();
            }
        }
        return weight == 0 ? null : sum.divide(BigDecimal.valueOf(weight), 4, RoundingMode.HALF_UP);
    }

    /** The measure's aggregate over a group made of several cells; {@code missing} when none has a value. */
    private static BigDecimal foldValue(Aggregation agg, List<Cell> cells, BigDecimal missing) {
        List<Cell> valued = cells.stream().filter(c -> c.value() != null).toList();
        if (valued.isEmpty()) {
            return missing;
        }
        return switch (agg) {
            case SUM, COUNT -> valued.stream().map(Cell::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            case MIN -> valued.stream().map(Cell::value).min(Comparator.naturalOrder()).orElse(missing);
            case MAX -> valued.stream().map(Cell::value).max(Comparator.naturalOrder()).orElse(missing);
            case AVG -> {
                BigDecimal sum = BigDecimal.ZERO;
                long weight = 0;
                for (Cell c : valued) {
                    sum = sum.add(c.value().multiply(BigDecimal.valueOf(c.measureCount())));
                    weight += c.measureCount();
                }
                yield weight == 0 ? missing : sum.divide(BigDecimal.valueOf(weight), 4, RoundingMode.HALF_UP);
            }
        };
    }

    /** The inclusive dates of the date bucket starting at {@code start}. */
    static ChartData.CategoryRange bucketRange(LocalDate start, com.financeos.domain.report.definition.Granularity g) {
        LocalDate end = switch (g) {
            case DAY -> start;
            case WEEK -> start.plusDays(6);
            case MONTH -> start.plusMonths(1).minusDays(1);
            case QUARTER -> start.plusMonths(3).minusDays(1);
            case YEAR, FY -> start.plusYears(1).minusDays(1);
        };
        return new ChartData.CategoryRange(start, end);
    }

    /** Collects cells while the executors group; keeps first-seen category and series order. */
    static final class Builder {
        private final List<String> categories = new ArrayList<>();
        private final Set<String> seenCategories = new HashSet<>();
        private final List<String> seriesNames = new ArrayList<>();
        private final Map<String, Map<String, Cell>> cells = new LinkedHashMap<>();
        private Map<String, ChartData.CategoryRange> ranges;

        void put(String category, String series, Cell cell) {
            if (seenCategories.add(category)) {
                categories.add(category);
            }
            series(series);
            cells.get(series).put(category, cell);
        }

        /** Registers a series (keeps registration order), so one without cells still appears. */
        void series(String name) {
            if (!cells.containsKey(name)) {
                seriesNames.add(name);
                cells.put(name, new HashMap<>());
            }
        }

        void range(String category, ChartData.CategoryRange range) {
            if (ranges == null) {
                ranges = new HashMap<>();
            }
            ranges.put(category, range);
        }

        /** Marks the result as date-bucketed, so {@code categoryRanges} is emitted even if empty. */
        void dated() {
            if (ranges == null) {
                ranges = new HashMap<>();
            }
        }

        Grouped build() {
            return new Grouped(categories, seriesNames, cells, ranges);
        }
    }
}
