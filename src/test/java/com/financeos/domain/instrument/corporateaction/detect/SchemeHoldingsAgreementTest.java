package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.instrument.corporateaction.detect.SchemeHoldingsAgreement.Check;
import com.financeos.domain.instrument.corporateaction.detect.SchemeHoldingsAgreement.SnapRow;
import com.financeos.domain.instrument.corporateaction.detect.SchemeHoldingsAgreement.Trade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchemeHoldingsAgreementTest {

    private static final UUID ACC = UUID.randomUUID();
    private static final String CHILD = "INE000CHILD01";
    private static final LocalDate LISTED = LocalDate.of(2025, 1, 29);

    private static BigDecimal q(String v) {
        return new BigDecimal(v);
    }

    private static SnapRow snap(String isin, String qty, LocalDate asOf) {
        return new SnapRow(isin, q(qty), asOf);
    }

    private static Trade buy(String qty, LocalDate d) {
        return new Trade(true, d, q(qty));
    }

    private static Trade sell(String qty, LocalDate d) {
        return new Trade(false, d, q(qty));
    }

    private static Check demerger(String parentQty, int from, int to, List<SnapRow> snap, List<Trade> trades) {
        return SchemeHoldingsAgreement.demerger(ACC, "Zerodha", q(parentQty), from, to, CHILD, LISTED, snap, trades);
    }

    @Test
    void theRatioIsFlooredToWholeShares() {
        assertThat(SchemeHoldingsAgreement.floorShares(q("25"), 10, 1)).isEqualByComparingTo("2");
        assertThat(SchemeHoldingsAgreement.floorShares(q("9"), 10, 1)).isEqualByComparingTo("0");
        assertThat(SchemeHoldingsAgreement.floorShares(q("100"), 5, 4)).isEqualByComparingTo("80");
        assertThat(SchemeHoldingsAgreement.floorShares(q("7"), 5, 4)).isEqualByComparingTo("5");
        assertThat(SchemeHoldingsAgreement.floorShares(q("0"), 5, 4)).isEqualByComparingTo("0");
    }

    @Test
    void aSnapshotAfterTheListingShowingTheExpectedChildSharesAgrees() {
        Check c = demerger("25", 10, 1, List.of(snap(CHILD, "2", LISTED.plusDays(5)), snap("INE000OTHER01", "40", LISTED.plusDays(5))), List.of());

        assertThat(c.agrees()).isTrue();
        assertThat(c.source()).isEqualTo("SNAPSHOT");
        assertThat(c.expectedQty()).isEqualByComparingTo("2");
        assertThat(c.foundQty()).isEqualByComparingTo("2");
        assertThat(c.asOf()).isEqualTo(LISTED.plusDays(5));
    }

    @Test
    void sellsBeforeTheSnapshotLowerTheExpectedQuantityAndBuysRaiseIt() {
        LocalDate asOf = LISTED.plusDays(30);
        List<Trade> trades = List.of(sell("30", LISTED.plusDays(3)), buy("5", LISTED.plusDays(10)), sell("9", asOf.plusDays(3))); // the last is after the snapshot

        Check c = demerger("100", 2, 1, List.of(snap(CHILD, "25", asOf)), trades);

        assertThat(c.agrees()).isTrue();
        assertThat(c.expectedQty()).isEqualByComparingTo("25"); // 50 - 30 + 5
    }

    @Test
    void aSnapshotThatMissesTheChildEntirelyAgreesOnlyWhenNothingIsExpected() {
        LocalDate asOf = LISTED.plusDays(2);
        assertThat(demerger("100", 2, 1, List.of(snap("INE000OTHER01", "10", asOf)), List.of(sell("50", LISTED.plusDays(1)))).agrees()).isTrue();
        assertThat(demerger("100", 2, 1, List.of(snap("INE000OTHER01", "10", asOf)), List.of()).agrees()).isFalse();
    }

    @Test
    void aSnapshotFromBeforeTheListingIsNoEvidence() {
        Check c = demerger("100", 2, 1, List.of(snap(CHILD, "0", LISTED.minusDays(1))), List.of());

        assertThat(c.source()).isEqualTo("NONE");
        assertThat(c.agrees()).isFalse();
    }

    @Test
    void aWrongQuantityDisagreesAndKeepsTheSnapshotAsTheEvidenceShown() {
        Check c = demerger("100", 2, 1, List.of(snap(CHILD, "49", LISTED.plusDays(1))), List.of());

        assertThat(c.agrees()).isFalse();
        assertThat(c.source()).isEqualTo("SNAPSHOT");
        assertThat(c.expectedQty()).isEqualByComparingTo("50");
        assertThat(c.foundQty()).isEqualByComparingTo("49");
    }

    @Test
    void theTradebookAloneAgreesWhenTheSellsBeforeAnyBuyAddUpToTheExpectedShares() {
        List<Trade> trades = List.of(sell("30", LISTED.plusDays(1)), sell("20", LISTED.plusDays(2)), buy("10", LISTED.plusDays(9)), sell("4", LISTED.plusDays(20)));

        Check c = demerger("100", 2, 1, List.of(), trades);

        assertThat(c.agrees()).isTrue();
        assertThat(c.source()).isEqualTo("TRADEBOOK");
        assertThat(c.foundQty()).isEqualByComparingTo("50");
        assertThat(c.asOf()).isEqualTo(LISTED.plusDays(2));
    }

    @Test
    void theTradebookDoesNotAgreeWhenASellIsPastABuyOrTheSumIsOff() {
        assertThat(demerger("100", 2, 1, List.of(), List.of(sell("30", LISTED.plusDays(1)), buy("10", LISTED.plusDays(2)), sell("20", LISTED.plusDays(3)))).agrees()).isFalse();
        assertThat(demerger("100", 2, 1, List.of(), List.of(sell("49", LISTED.plusDays(1)))).agrees()).isFalse();
        assertThat(demerger("100", 2, 1, List.of(), List.of(sell("51", LISTED.plusDays(1)))).agrees()).isFalse();
        assertThat(demerger("100", 2, 1, List.of(), List.of()).source()).isEqualTo("NONE");
    }

    @Test
    void theTradebookRescuesADisagreeingSnapshot() {
        // the snapshot still shows 5 child shares, but the user sold exactly the 50 that arrived, then bought 5 later
        List<Trade> trades = List.of(sell("50", LISTED.plusDays(1)), buy("5", LISTED.plusDays(40)));

        Check c = demerger("100", 2, 1, List.of(snap(CHILD, "9", LISTED.plusDays(60))), trades);

        assertThat(c.agrees()).isTrue();
        assertThat(c.source()).isEqualTo("TRADEBOOK");
    }

    @Test
    void anAccountWithNothingExpectedNeverAgreesFromTheTradebook() {
        assertThat(demerger("5", 10, 1, List.of(), List.of()).agrees()).isFalse();
    }

    @Test
    void everyAccountMustAgreeAndThereMustBeOne() {
        Check ok = demerger("100", 2, 1, List.of(snap(CHILD, "50", LISTED.plusDays(1))), List.of());
        Check bad = demerger("100", 2, 1, List.of(snap(CHILD, "40", LISTED.plusDays(1))), List.of());

        assertThat(SchemeHoldingsAgreement.allAgree(List.of(ok, ok))).isTrue();
        assertThat(SchemeHoldingsAgreement.allAgree(List.of(ok, bad))).isFalse();
        assertThat(SchemeHoldingsAgreement.allAgree(List.of())).isFalse();
    }

    // ---- merger

    private static Check merger(String parentQty, int from, int to, LocalDate record, List<SnapRow> snap, List<Trade> trades) {
        return SchemeHoldingsAgreement.merger(ACC, "Groww", q(parentQty), from, to, "INE000ACQ0001", record, snap, trades);
    }

    @Test
    void aMergerSnapshotMustShowExactlyTheExpectedQuantity() {
        LocalDate record = LocalDate.of(2022, 6, 7);
        LocalDate asOf = record.plusDays(20);
        List<Trade> own = List.of(buy("30", record.minusDays(100)), sell("10", record.plusDays(5)));

        Check exact = merger("140", 14, 13, record, List.of(snap("INE000ACQ0001", "150", asOf)), own);
        Check off = merger("140", 14, 13, record, List.of(snap("INE000ACQ0001", "149", asOf)), own);

        assertThat(exact.agrees()).isTrue();
        assertThat(exact.expectedQty()).isEqualByComparingTo("150"); // 20 the app had + floor(140 x 13 / 14) = 130
        assertThat(off.agrees()).isFalse();
    }

    @Test
    void aMergerNeedsASnapshotDatedOnOrAfterTheRecordDate() {
        LocalDate record = LocalDate.of(2022, 6, 7);

        assertThat(merger("140", 14, 13, record, List.of(snap("INE000ACQ0001", "130", record.minusDays(1))), List.of()).source()).isEqualTo("NONE");
        assertThat(merger("140", 14, 13, record, List.of(), List.of()).agrees()).isFalse();
        assertThat(merger("140", 14, 13, null, List.of(snap("INE000ACQ0001", "130", record)), List.of()).agrees()).isFalse();
        assertThat(merger("140", 14, 13, record, List.of(snap("INE000ACQ0001", "130", record)), List.of()).agrees()).isTrue();
    }

    @Test
    void aTradeOnTheSnapshotDateVoidsTheSnapshotEvidenceForADemerger() {
        LocalDate asOf = LISTED.plusDays(10);

        Check onDate = demerger("100", 2, 1, List.of(snap(CHILD, "50", asOf)), List.of(buy("1", asOf)));
        Check before = demerger("100", 2, 1, List.of(snap(CHILD, "51", asOf)), List.of(buy("1", asOf.minusDays(1))));

        assertThat(onDate.source()).isEqualTo("NONE");
        assertThat(onDate.agrees()).isFalse();
        assertThat(onDate.asOf()).isEqualTo(asOf);
        assertThat(before.agrees()).isTrue(); // a buy the day before is counted: 50 + 1
    }

    @Test
    void aTradeOnTheSnapshotDateVoidsTheSnapshotEvidenceForAMerger() {
        LocalDate record = LocalDate.of(2022, 6, 7);
        LocalDate asOf = record.plusDays(20);

        Check onDate = merger("140", 14, 13, record, List.of(snap("INE000ACQ0001", "130", asOf)), List.of(sell("1", asOf)));
        Check before = merger("140", 14, 13, record, List.of(snap("INE000ACQ0001", "129", asOf)), List.of(sell("1", asOf.minusDays(1))));

        assertThat(onDate.source()).isEqualTo("NONE");
        assertThat(onDate.agrees()).isFalse();
        assertThat(before.agrees()).isTrue(); // 130 given less the 1 sold the day before
    }

    // ---- neutral accounts and the tradebook's start day

    @Test
    void anAccountThatGetsNoChildShareIsNeutralAndNeverEvidence() {
        Check neutral = demerger("5", 10, 1, List.of(snap(CHILD, "0", LISTED.plusDays(1))), List.of());
        Check ok = demerger("100", 2, 1, List.of(snap(CHILD, "50", LISTED.plusDays(1))), List.of());
        Check bad = demerger("100", 2, 1, List.of(snap(CHILD, "40", LISTED.plusDays(1))), List.of());

        assertThat(neutral.neutral()).isTrue();
        assertThat(ok.neutral()).isFalse();
        assertThat(SchemeHoldingsAgreement.allAgree(List.of(neutral))).isFalse();
        assertThat(SchemeHoldingsAgreement.allAgree(List.of(neutral, neutral))).isFalse();
        assertThat(SchemeHoldingsAgreement.allAgree(List.of(ok, neutral))).isTrue();
        assertThat(SchemeHoldingsAgreement.allAgree(List.of(neutral, bad))).isFalse();
    }

    @Test
    void aMergerAccountThatGetsNothingIsNeutral() {
        LocalDate record = LocalDate.of(2025, 3, 10);

        Check c = merger("1", 14, 13, record, List.of(snap("INE000ACQ0001", "0", record)), List.of());

        assertThat(c.neutral()).isTrue();
        assertThat(SchemeHoldingsAgreement.allAgree(List.of(c))).isFalse();
    }

    @Test
    void aTradebookSellBeforeTheListingIsNotThePartOfTheChild() {
        // 20 sold before the child existed (an older holding), then exactly the 50 that arrived
        List<Trade> trades = List.of(sell("20", LISTED.minusDays(3)), sell("50", LISTED.plusDays(1)));

        Check c = demerger("100", 2, 1, List.of(), trades);

        assertThat(c.agrees()).isTrue();
        assertThat(c.source()).isEqualTo("TRADEBOOK");
        assertThat(c.foundQty()).isEqualByComparingTo("50");
        // the same 50 sold before the listing is no evidence at all
        assertThat(demerger("100", 2, 1, List.of(), List.of(sell("50", LISTED.minusDays(1)))).agrees()).isFalse();
    }

    @Test
    void aTradebookSellBeforeTheExDateCountsNothingEvenAfterAnEarlyListingDate() {
        LocalDate ex = LISTED.plusDays(10);

        Check c = SchemeHoldingsAgreement.demerger(ACC, "Zerodha", q("100"), 2, 1, CHILD, LISTED, ex, List.of(),
                List.of(sell("50", LISTED.plusDays(5))));
        Check onEx = SchemeHoldingsAgreement.demerger(ACC, "Zerodha", q("100"), 2, 1, CHILD, LISTED, ex, List.of(), List.of(sell("50", ex)));

        assertThat(c.agrees()).isFalse();
        assertThat(onEx.agrees()).isTrue();
    }
}
