package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartOrder;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Top N + Other, ordering, previous-period alignment and breakdown rows, independent of the executors. */
class ChartShaperTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static ChartShaper.Cell cell(String value, long count) {
        return new ChartShaper.Cell(d(value), count, count, count == 0 ? null : d(value).divide(BigDecimal.valueOf(count), 4, java.math.RoundingMode.HALF_UP));
    }

    /** Single-series grouped result, categories in the given (dimension) order. */
    private static ChartShaper.Grouped grouped(String... catValueCount) {
        ChartShaper.Builder b = new ChartShaper.Builder();
        b.series("amount");
        for (String s : catValueCount) {
            String[] p = s.split(":");
            b.put(p[0], "amount", cell(p[1], Long.parseLong(p[2])));
        }
        return b.build();
    }

    private static ChartDefinition def(ChartType type, Integer limit, Boolean other, ChartOrder order, Comparison cmp) {
        return new ChartDefinition(type, new DimensionRef("category", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of(), limit, other, order, cmp, null, null);
    }

    private static List<BigDecimal> data(ChartShaper.Shaped s, int series) {
        return s.series().get(series).data();
    }

    private static final ChartShaper.Grouped FIVE = grouped("A:10:1", "B:50:2", "C:-30:3", "D:20:4", "E:5:5");

    @Test
    void noLimitKeepsEverythingInDimensionOrder() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, null, null, null, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("A", "B", "C", "D", "E"), s.categories());
        assertNull(s.other());
        assertNull(s.previousSeries());
        assertNull(s.breakdown());
        assertNull(s.categoryRanges());
    }

    @Test
    void limitKeepsTopByAbsoluteValueAndDropsTheRestWithoutOther() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 3, null, null, null), Aggregation.SUM, FIVE, null, null);
        // B(50), C(|-30|), D(20) survive; display stays in dimension order
        assertEquals(List.of("B", "C", "D"), s.categories());
        assertEquals(List.of(d("50"), d("-30"), d("20")), data(s, 0));
        assertNull(s.other());
    }

    @Test
    void otherFoldsTheRestLastAndReportsIndexAndCount() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 3, true, null, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("B", "C", "D", "Other"), s.categories());
        assertEquals(List.of(d("50"), d("-30"), d("20"), d("15")), data(s, 0));
        assertEquals(3, s.other().index());
        assertEquals(2, s.other().foldedCount());
    }

    @Test
    void otherWithNothingToFoldIsAbsent() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 5, true, null, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("A", "B", "C", "D", "E"), s.categories());
        assertNull(s.other());
    }

    @Test
    void otherOffByDefaultEvenWithALimit() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 2, false, null, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(2, s.categories().size());
        assertNull(s.other());
    }

    @Test
    void orderVariantsApplyAfterTheFoldAndOtherStaysLast() {
        ChartShaper.Shaped desc = ChartShaper.shape(def(ChartType.BAR, 3, true, ChartOrder.VALUE_DESC, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("B", "C", "D", "Other"), desc.categories());
        ChartShaper.Shaped asc = ChartShaper.shape(def(ChartType.BAR, 3, true, ChartOrder.VALUE_ASC, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("D", "C", "B", "Other"), asc.categories());
        assertEquals(List.of(d("20"), d("-30"), d("50"), d("15")), data(asc, 0));
        ChartShaper.Shaped dim = ChartShaper.shape(def(ChartType.BAR, 3, true, ChartOrder.DIMENSION, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("B", "C", "D", "Other"), dim.categories());
    }

    @Test
    void orderWithoutLimitReordersAllCategories() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, null, null, ChartOrder.VALUE_DESC, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("B", "C", "D", "A", "E"), s.categories());
    }

    @Test
    void breakdownDefaultsToValueDescending() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BREAKDOWN, null, null, null, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("B", "C", "D", "A", "E"), s.categories());
        ChartShaper.Shaped byDim = ChartShaper.shape(def(ChartType.BREAKDOWN, null, null, ChartOrder.DIMENSION, null), Aggregation.SUM, FIVE, null, null);
        assertEquals(List.of("A", "B", "C", "D", "E"), byDim.categories());
    }

    @Test
    void equalValuesKeepDimensionOrder() {
        ChartShaper.Grouped tie = grouped("A:10:1", "B:10:1", "C:10:1");
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 2, null, ChartOrder.VALUE_DESC, null), Aggregation.SUM, tie, null, null);
        assertEquals(List.of("A", "B"), s.categories());
    }

    @Test
    void aRealCategoryCalledOtherIsJustAnotherCategory() {
        ChartShaper.Grouped g = grouped("Other:100:1", "A:5:1", "B:4:1", "C:3:1");
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 2, true, null, null), Aggregation.SUM, g, null, null);
        assertEquals(List.of("Other", "A", "Other"), s.categories());
        assertEquals(List.of(d("100"), d("5"), d("7")), data(s, 0));
        assertEquals(2, s.other().index());
        assertEquals(2, s.other().foldedCount());
    }

    @Test
    void noneBucketRanksAndFoldsLikeAnyCategory() {
        ChartShaper.Grouped g = grouped("(none):100:1", "A:5:1", "B:4:1");
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 1, true, null, null), Aggregation.SUM, g, null, null);
        assertEquals(List.of("(none)", "Other"), s.categories());
        assertEquals(List.of(d("100"), d("9")), data(s, 0));
    }

    @Test
    void withSeriesCategoriesRankByTheirTotalAcrossSeries() {
        ChartShaper.Builder b = new ChartShaper.Builder();
        b.put("A", "debit", cell("10", 1));
        b.put("A", "credit", cell("10", 1));   // total 20
        b.put("B", "debit", cell("15", 1));    // total 15
        b.put("C", "credit", cell("12", 1));   // total 12
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.STACKED_BAR, 2, true, null, null), Aggregation.SUM, b.build(), null, null);
        assertEquals(List.of("A", "B", "Other"), s.categories());
        assertEquals(List.of("debit", "credit"), s.series().stream().map(ChartData.Series::name).toList());
        assertEquals(List.of(d("10"), d("15"), d("0")), data(s, 0));
        assertEquals(List.of(d("10"), d("0"), d("12")), data(s, 1));
    }

    @Test
    void nonAdditiveFoldsUseWeightedAverageMinAndMax() {
        ChartShaper.Builder b = new ChartShaper.Builder();
        b.put("A", "amount", new ChartShaper.Cell(d("100"), 1, 1, d("100")));
        b.put("B", "amount", new ChartShaper.Cell(d("10"), 1, 1, d("10")));
        b.put("C", "amount", new ChartShaper.Cell(d("20"), 3, 3, d("20")));
        ChartShaper.Grouped g = b.build();
        ChartDefinition avgDef = def(ChartType.BAR, 1, true, null, null);
        assertEquals(d("17.5000"), data(ChartShaper.shape(avgDef, Aggregation.AVG, g, null, null), 0).get(1));
        assertEquals(d("10"), data(ChartShaper.shape(avgDef, Aggregation.MIN, g, null, null), 0).get(1));
        assertEquals(d("20"), data(ChartShaper.shape(avgDef, Aggregation.MAX, g, null, null), 0).get(1));
    }

    // ------------------------------------------------------------------ comparison

    @Test
    void previousAlignsByLabelWithZeroForMissingAndOtherAsTheSumOfNotKept() {
        ChartShaper.Grouped previous = grouped("A:7:1", "B:40:2", "Z:3:1", "E:2:1"); // C, D absent; Z only in previous
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 3, true, null, null), Aggregation.SUM, FIVE, previous, null);
        assertEquals(List.of("B", "C", "D", "Other"), s.categories());
        assertEquals("amount", s.previousSeries().get(0).name());
        // B=40, C missing -> 0, D missing -> 0, Other = A+Z+E = 12
        assertEquals(List.of(d("40"), d("0"), d("0"), d("12")), s.previousSeries().get(0).data());
        assertEquals(s.categories().size(), s.previousSeries().get(0).data().size());
    }

    @Test
    void previousHasNoOtherEntryWhenNothingIsFolded() {
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 2, false, null, null), Aggregation.SUM, FIVE,
                grouped("B:40:1", "A:1:1"), null);
        assertEquals(List.of("B", "C"), s.categories());
        assertEquals(List.of(d("40"), d("0")), s.previousSeries().get(0).data());
    }

    @Test
    void previousWithSeriesUsesTheCurrentSeriesNamesAndZeroFillsOthers() {
        ChartShaper.Builder cur = new ChartShaper.Builder();
        cur.put("A", "debit", cell("10", 1));
        cur.put("A", "credit", cell("5", 1));
        ChartShaper.Builder prev = new ChartShaper.Builder();
        prev.put("A", "debit", cell("8", 1));
        prev.put("A", "refund", cell("99", 1)); // series not in the current result: ignored
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, null, null, null, null), Aggregation.SUM, cur.build(), prev.build(), null);
        assertEquals(List.of("debit", "credit"), s.previousSeries().stream().map(ChartData.Series::name).toList());
        assertEquals(List.of(d("8")), s.previousSeries().get(0).data());
        assertEquals(List.of(d("0")), s.previousSeries().get(1).data());
    }

    // ------------------------------------------------------------------ breakdown

    @Test
    void breakdownRowsCarryCountAverageSubRowsAndTotals() {
        Map<String, List<ChartShaper.SubCell>> subs = Map.of(
                "B", List.of(new ChartShaper.SubCell("x", d("5"), 1), new ChartShaper.SubCell("y", d("30"), 2),
                        new ChartShaper.SubCell("z", d("10"), 1), new ChartShaper.SubCell("w", d("-20"), 1)),
                "A", List.of(new ChartShaper.SubCell("only", d("10"), 1)));
        ChartDefinition def = new ChartDefinition(ChartType.BREAKDOWN, new DimensionRef("category", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of(), 2, true, null, null, new DimensionRef("account", null), 2);
        ChartShaper.Shaped s = ChartShaper.shape(def, Aggregation.SUM, FIVE, grouped("B:40:4", "A:1:1"), subs);

        assertEquals(List.of("B", "C", "Other"), s.categories());
        ChartData.Breakdown bd = s.breakdown();
        assertEquals(3, bd.rows().size());
        ChartData.BreakdownRow b = bd.rows().get(0);
        assertEquals(2, b.count());
        assertEquals(d("25.0000"), b.avg());
        assertEquals(4L, b.previousCount());
        assertEquals(List.of("y", "w"), b.subRows().stream().map(ChartData.SubRow::label).toList()); // top 2 by |value|
        assertEquals(2, b.subRows().get(0).count());
        assertEquals(0L, bd.rows().get(1).previousCount());
        ChartData.BreakdownRow other = bd.rows().get(2);
        assertEquals(1 + 4 + 5, other.count()); // A, D, E
        assertEquals(List.of(), other.subRows());
        assertEquals(1L, other.previousCount()); // previous of every not-kept: A(1); C absent
        assertEquals(d("55"), bd.total());      // 10+50-30+20+5, all categories
        assertEquals(d("41"), bd.previousTotal());
    }

    @Test
    void breakdownSubLimitDefaultsToThreeAndPreviousIsNullWithoutAComparison() {
        List<ChartShaper.SubCell> many = List.of(new ChartShaper.SubCell("a", d("1"), 1), new ChartShaper.SubCell("b", d("2"), 1),
                new ChartShaper.SubCell("c", d("3"), 1), new ChartShaper.SubCell("d", d("4"), 1));
        ChartDefinition def = new ChartDefinition(ChartType.BREAKDOWN, new DimensionRef("category", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of(), null, null, null, null, new DimensionRef("account", null), null);
        ChartShaper.Shaped s = ChartShaper.shape(def, Aggregation.SUM, grouped("A:10:1"), null, Map.of("A", many));
        assertEquals(List.of("d", "c", "b"), s.breakdown().rows().get(0).subRows().stream().map(ChartData.SubRow::label).toList());
        assertNull(s.breakdown().rows().get(0).previousCount());
        assertNull(s.breakdown().previousTotal());
    }

    // ------------------------------------------------------------------ category ranges

    @Test
    void bucketRangesCoverTheWholeBucket() {
        assertEquals(LocalDate.of(2026, 2, 28), ChartShaper.bucketRange(LocalDate.of(2026, 2, 1), Granularity.MONTH).to());
        assertEquals(LocalDate.of(2026, 6, 30), ChartShaper.bucketRange(LocalDate.of(2026, 4, 1), Granularity.QUARTER).to());
        assertEquals(LocalDate.of(2026, 12, 31), ChartShaper.bucketRange(LocalDate.of(2026, 1, 1), Granularity.YEAR).to());
        assertEquals(LocalDate.of(2027, 3, 31), ChartShaper.bucketRange(LocalDate.of(2026, 4, 1), Granularity.FY).to());
        assertEquals(LocalDate.of(2026, 3, 8), ChartShaper.bucketRange(LocalDate.of(2026, 3, 2), Granularity.WEEK).to());
        assertEquals(LocalDate.of(2026, 3, 2), ChartShaper.bucketRange(LocalDate.of(2026, 3, 2), Granularity.DAY).to());
    }

    @Test
    void categoryRangesFollowTheDisplayedCategoriesAndOtherHasNone() {
        ChartShaper.Builder b = new ChartShaper.Builder();
        b.dated();
        b.put("Jan 26", "amount", cell("10", 1));
        b.range("Jan 26", ChartShaper.bucketRange(LocalDate.of(2026, 1, 1), Granularity.MONTH));
        b.put("Feb 26", "amount", cell("30", 1));
        b.range("Feb 26", ChartShaper.bucketRange(LocalDate.of(2026, 2, 1), Granularity.MONTH));
        b.put("Mar 26", "amount", cell("20", 1));
        b.range("Mar 26", ChartShaper.bucketRange(LocalDate.of(2026, 3, 1), Granularity.MONTH));
        ChartShaper.Shaped s = ChartShaper.shape(def(ChartType.BAR, 2, true, ChartOrder.VALUE_DESC, null), Aggregation.SUM, b.build(), null, null);
        assertEquals(List.of("Feb 26", "Mar 26", "Other"), s.categories());
        assertEquals(3, s.categoryRanges().size());
        assertEquals(LocalDate.of(2026, 2, 1), s.categoryRanges().get(0).from());
        assertEquals(LocalDate.of(2026, 3, 31), s.categoryRanges().get(1).to());
        assertNull(s.categoryRanges().get(2));
        assertNotNull(s.other());
    }

    @Test
    void breakdownTotalsAreNullForNonAdditiveAggregations() {
        ChartDefinition def = new ChartDefinition(ChartType.BREAKDOWN, new DimensionRef("category", null), null,
                new MeasureRef("amount", Aggregation.AVG), List.of(), null, null, null, null, null, null);
        for (Aggregation agg : new Aggregation[]{Aggregation.AVG, Aggregation.MIN, Aggregation.MAX}) {
            ChartData.Breakdown bd = ChartShaper.shape(def, agg, FIVE, grouped("B:40:4"), null).breakdown();
            assertNull(bd.total(), agg.name());
            assertNull(bd.previousTotal(), agg.name());
            assertEquals(5, bd.rows().size());
        }
        for (Aggregation agg : new Aggregation[]{Aggregation.SUM, Aggregation.COUNT}) {
            ChartData.Breakdown bd = ChartShaper.shape(def, agg, FIVE, grouped("B:40:4"), null).breakdown();
            assertEquals(d("55"), bd.total(), agg.name());
            assertEquals(d("40"), bd.previousTotal(), agg.name());
        }
    }

    @Test
    void equalSubRowValuesTieBreakOnLabelWhateverTheInputOrder() {
        ChartDefinition def = new ChartDefinition(ChartType.BREAKDOWN, new DimensionRef("category", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of(), null, null, null, null, new DimensionRef("account", null), 2);
        List<ChartShaper.SubCell> one = List.of(new ChartShaper.SubCell("zeta", d("10"), 1), new ChartShaper.SubCell("alpha", d("-10"), 1),
                new ChartShaper.SubCell("mid", d("10"), 1), new ChartShaper.SubCell("big", d("11"), 1));
        List<ChartShaper.SubCell> reversed = List.of(one.get(3), one.get(2), one.get(1), one.get(0));
        for (List<ChartShaper.SubCell> input : List.of(one, reversed)) {
            ChartShaper.Shaped s = ChartShaper.shape(def, Aggregation.SUM, grouped("A:10:1"), null, Map.of("A", input));
            assertEquals(List.of("big", "alpha"), s.breakdown().rows().get(0).subRows().stream().map(ChartData.SubRow::label).toList());
        }
    }
}
