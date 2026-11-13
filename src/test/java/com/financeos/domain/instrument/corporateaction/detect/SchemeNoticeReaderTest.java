package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.instrument.corporateaction.detect.NseListingsClient.Listing;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.Child;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.Kind;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.NoticeText;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.Reading;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The notice reader on short excerpts of companies' public exchange filings (and plain made-up sentences for the
 * negative cases). Ratios are stated as child shares : parent shares, stored as {@code ratioTo : ratioFrom}.
 */
class SchemeNoticeReaderTest {

    private static final LocalDate EX = LocalDate.of(2025, 1, 6);

    private static NoticeText notice(String url, String date, String summary, String pdf) {
        return new NoticeText(url, LocalDate.parse(date), summary, pdf, false);
    }

    private static Listing listing(String symbol, String name, LocalDate listed) {
        return new Listing(symbol, name, listed, "INE000" + symbol.hashCode());
    }

    private static Reading demerger(String parentName, String parentSymbol, LocalDate ex, List<NoticeText> notices, Listing... listed) {
        java.util.List<Listing> all = new java.util.ArrayList<>(List.of(listed));
        all.add(new Listing(parentSymbol, parentName, LocalDate.of(1995, 1, 1), "INE000PARENT"));
        return SchemeNoticeReader.read(Kind.demerger, parentName, parentSymbol, "INE000PARENT", ex, notices, all);
    }

    // ---- ratios from real wording

    @Test
    void shareEntitlementInTheOtherOrderIsRead() {
        // from a company's scheme intimation: the parent's shares come first
        String pdf = "in consideration, the consequent issuance of equity shares by the Resulting Company to all the shareholders of the "
                + "Demerged Company in accordance with the Share Entitlement Ratio i.e. “for every 10 (Ten) Ordinary Shares of face and "
                + "paid-up value of Re. 1 each held in the Demerged Company, 1 (One) equity share of face and paid-up value of Re. 1 in the "
                + "Resulting Company”. Further details as required under Regulation 30 of the SEBI (Listing Obligations and Disclosure Requirements)";
        Reading r = demerger("ITC Limited", "ITC", EX, List.of(notice("https://x.example/scheme.pdf", "2023-08-14", "Scheme of arrangement", pdf)),
                listing("ITCHOTELS", "ITC Hotels Limited", EX.plusDays(23)));
        // the child is named nowhere in this excerpt: not a child yet
        assertThat(r.children()).isEmpty();

        Reading named = demerger("ITC Limited", "ITC", EX, List.of(
                        notice("https://x.example/scheme.pdf", "2023-08-14", "Scheme of arrangement of ITC Limited and ITC Hotels Limited", pdf)),
                listing("ITCHOTELS", "ITC Hotels Limited", EX.plusDays(23)));
        assertThat(named.children()).hasSize(1);
        Child c = named.children().get(0);
        assertThat(c.listing().symbol()).isEqualTo("ITCHOTELS");
        assertThat(c.ratioFrom()).isEqualTo(10);
        assertThat(c.ratioTo()).isEqualTo(1);
        assertThat(c.ratioSource().url()).isEqualTo("https://x.example/scheme.pdf");
        assertThat(c.ratioSource().date()).isEqualTo(LocalDate.of(2023, 8, 14));
        assertThat(c.ratioSource().quote()).contains("for every 10 (Ten) Ordinary Shares").hasSizeLessThanOrEqualTo(300);
    }

    @Test
    void aShortNameForTheChildStillGivesTheRatioOfASingleChild() {
        // "TMLCV" is the notice's own abbreviation; the child is named in the summary line
        String pdf = "The Company has fixed Tuesday, October 14, 2025, as the “Record Date” to ascertain eligible shareholders of the Company who "
                + "shall be issued and allotted 1 (one) share (face value of INR 2/- each fully paid up) in TMLCV, for every 1 (one) share "
                + "(face value of INR 2/- each fully paid up) held in TML by them as on the Record Date (as per Share Entitlement Ratio under the Scheme).";
        LocalDate ex = LocalDate.of(2025, 10, 14);
        Reading r = demerger("Tata Motors Limited", "TATAMOTORS", ex, List.of(notice("https://x.example/rd.pdf", "2025-10-09",
                        "Demerger record date. Resulting company: Tata Motors Passenger Vehicles Limited", pdf)),
                listing("TMCVX", "Tata Motors Passenger Vehicles Limited", ex.plusDays(29)));

        assertThat(r.children()).hasSize(1);
        assertThat(r.children().get(0).ratioFrom()).isEqualTo(1);
        assertThat(r.children().get(0).ratioTo()).isEqualTo(1);
        assertThat(r.recordDate()).isEqualTo(LocalDate.of(2025, 10, 14));
    }

    @Test
    void fourSharesForEveryFiveIsReadAndAnotherMergersRatioInTheSameNoticeIsLeftOut() {
        String pdf = "In terms of the sanctioned Scheme: 1. Each equity shareholder of Raymond Limited as on the Record Date, will be issued and "
                + "allotted 4 (four) fully paid-up equity share(s) of INR 2 (Indian Rupees Two) each of Raymond Lifestyle Limited for every 5 (five) "
                + "fully paid-up equity share(s) of INR 10 (Indian Rupees Ten) each of the Raymond Limited. 2. Each equity shareholder of Ray Global "
                + "Consumer Trading Limited as on record date (other than itself) will be issued and allotted 2 (Two) equity share of Raymond "
                + "Lifestyle Limited (formerly known as Raymond Consumer Care Limited) of INR 2 each fully paid up for every 1 (One) fully paid-up "
                + "equity share(s) of of INR 10 each fully paid up of Ray Global Consumer Trading Limited.";
        LocalDate ex = LocalDate.of(2024, 7, 11);
        Reading r = demerger("Raymond Limited", "RAYMOND", ex, List.of(notice("https://x.example/rd.pdf", "2024-07-01", "Record date", pdf)),
                listing("RAYMONDLSL", "Raymond Lifestyle Limited", ex.plusDays(56)));

        Child c = r.children().get(0);
        assertThat(c.ratioFrom()).isEqualTo(5);
        assertThat(c.ratioTo()).isEqualTo(4);
        assertThat(r.missing()).doesNotContain(SchemeNoticeReader.NOTICES_DISAGREE);
    }

    // ---- cost split from real layouts

    @Test
    void aCostTableWithTheNamesBeforeThePercentagesInAnyOrder() {
        String pdf = "the apportionment of the cost of acquisition is as under: Name of the Company Sr No 1 Aarti Pharmalabs Limited (Resulting "
                + "Company) 2 Aarti Industries Limited (Demerged Company) Total Percentage of cost of acquisition of equity shares 24.42% 75.58% "
                + "100.00% Thus, the cost of acquisition of equity shares allotted by Aarti Pharmalabs Limited is calculated as 24.42% of the "
                + "total cost of acquisition of the equity shares held in Aarti Industries Limited prior to the demerger.";
        LocalDate ex = LocalDate.of(2022, 10, 19);
        Reading r = demerger("Aarti Industries Limited", "AARTIIND", ex, List.of(notice("https://x.example/cost.pdf", "2022-10-31", "Cost of acquisition", pdf)),
                listing("AARTIPHARM", "Aarti Pharmalabs Limited", ex.plusDays(103)));

        assertThat(r.parentCostPct()).isEqualByComparingTo("75.58");
        assertThat(r.children().get(0).costPct()).isEqualByComparingTo("24.42");
        assertThat(r.costSource().url()).isEqualTo("https://x.example/cost.pdf");
        assertThat(r.costSource().quote()).contains("24.42%").hasSizeLessThanOrEqualTo(300);
        assertThat(r.missing()).doesNotContain(SchemeNoticeReader.COST_NOT_PUBLISHED_YET, SchemeNoticeReader.COST_UNCLEAR);
    }

    @Test
    void aCostTableWithEachPercentageAfterItsName() {
        String pdf = "Name of Company NMDC Limited Percentage of Cost of Acquisition of equity shares in Demerged and Resulting Company 42.71% "
                + "(Demerged Company) NMDC Steel Limited 57.29% (Resulting Company) Total 100.00% This communication related with computation "
                + "of cost of acquisition is meant only for the purposes of providing general guidance to the shareholders.";
        LocalDate ex = LocalDate.of(2022, 10, 27);
        Reading r = demerger("NMDC Limited", "NMDC", ex, List.of(notice("https://x.example/c.pdf", "2022-10-17", "", pdf)),
                listing("NSLNISP", "NMDC Steel Limited", ex.plusDays(116)));

        assertThat(r.parentCostPct()).isEqualByComparingTo("42.71");
        assertThat(r.children().get(0).costPct()).isEqualByComparingTo("57.29");
    }

    @Test
    void aCostTableOfNamesThenPercentagesAddingUpTo100() {
        String pdf = "the proportionate cost of acquisition as per Sections 49(2C) and 49(2D) of the Income Tax Act, 1961 is as under: Sr No 1 2 "
                + "Name of the Company GMR Infrastructure Limited GMR Power and Urban Infra Limited Total % of total Cost of Acquisition of the "
                + "Equity Shares 92.71% 7.29% 100% Accordingly, the cost of acquisition of equity shares allotted in GPUIL should be 7.29% of the "
                + "total cost of acquisition of the equity shares held in GIL prior to the demerger.";
        LocalDate ex = LocalDate.of(2022, 1, 11);
        Reading r = demerger("GMR Airports Infrastructure Limited", "GMRAIRPORT", ex,
                List.of(notice("https://x.example/c.pdf", "2022-01-31", "Cost of acquisition", pdf)),
                listing("GMRPUI", "GMR Power and Urban Infra Limited", ex.plusDays(71)),
                new Listing("GMRINFRA", "GMR Infrastructure Limited", LocalDate.of(2006, 1, 1), "INE000PARENT"));

        assertThat(r.parentCostPct()).isEqualByComparingTo("92.71");
        assertThat(r.children().get(0).costPct()).isEqualByComparingTo("7.29");
    }

    // ---- two children

    @Test
    void twoChildrenGetTheirOwnRatiosAndCostShares() {
        String pdf = "Record date for the demerger. Shareholders of Acme Industries Limited will receive 1 (One) equity share of Acme Foods Limited "
                + "for every 5 (Five) equity shares held in Acme Industries Limited and 2 (Two) equity shares of Acme Power Limited for every "
                + "1 (One) equity share held in Acme Industries Limited. Cost of acquisition: Acme Industries Limited 70.00% Acme Foods Limited "
                + "20.00% Acme Power Limited 10.00% Total 100.00%";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/rd.pdf", "2025-01-02", "Record date", pdf)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)), listing("ACMEPOWER", "Acme Power Limited", EX.plusDays(22)));

        assertThat(r.children()).extracting(c -> c.listing().symbol()).containsExactly("ACMEFOODS", "ACMEPOWER");
        Child foods = r.children().get(0);
        Child power = r.children().get(1);
        assertThat(foods.ratioFrom()).isEqualTo(5);
        assertThat(foods.ratioTo()).isEqualTo(1);
        assertThat(power.ratioFrom()).isEqualTo(1);
        assertThat(power.ratioTo()).isEqualTo(2);
        assertThat(r.parentCostPct()).isEqualByComparingTo("70.00");
        assertThat(foods.costPct()).isEqualByComparingTo("20.00");
        assertThat(power.costPct()).isEqualByComparingTo("10.00");
    }

    @Test
    void withSeveralChildrenASentenceThatNamesNoneIsNotGuessedFor() {
        String pdf = "Shareholders will receive 1 (One) equity share for every 5 (Five) equity shares held. The scheme demerges Acme Foods Limited "
                + "and Acme Power Limited from Acme Industries Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", "Demerger", pdf)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)), listing("ACMEPOWER", "Acme Power Limited", EX.plusDays(22)));

        assertThat(r.children()).hasSize(2);
        assertThat(r.children()).allSatisfy(c -> assertThat(c.ratioFrom()).isNull());
        assertThat(r.missing()).contains(SchemeNoticeReader.RATIO_NOT_FOUND);
    }

    // ---- children

    @Test
    void onlyListingsInTheFourHundredDaysAfterTheExDateAreChildren() {
        String text = "Demerger of Acme Industries Limited: Acme Foods Limited and Acme Power Limited are the resulting companies.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", text, null)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(400)), listing("ACMEPOWER", "Acme Power Limited", EX.plusDays(401)),
                listing("ACMEOLD", "Acme Power Limited", EX));

        assertThat(r.children()).extracting(c -> c.listing().symbol()).containsExactly("ACMEFOODS");
    }

    @Test
    void aListedCompanyNotNamedInANoticeIsNotAChildEvenWithASimilarName() {
        String text = "Demerger of Acme Industries Limited into Acme Foods Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", text, null)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)), listing("ACMEFOODSIND", "Acme Foods India Limited", EX.plusDays(20)));

        assertThat(r.children()).extracting(c -> c.listing().symbol()).containsExactly("ACMEFOODS");
    }

    @Test
    void aFormerNameStatedInTheNoticesFindsTheRenamedChild() {
        String former = "The shares of Acme Beverages Limited (formerly known as Acme Foods Limited) were listed. ";
        String text = "Demerger of Acme Industries Limited into Acme Foods Limited. 1 (One) equity share of Acme Foods Limited for every 4 (Four) "
                + "equity shares of Acme Industries Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(
                        notice("https://x.example/a.pdf", "2025-03-01", former, null), notice("https://x.example/b.pdf", "2025-01-02", text, null)),
                listing("ACMEBEV", "Acme Beverages Limited", EX.plusDays(20)));

        assertThat(r.children()).extracting(c -> c.listing().symbol()).containsExactly("ACMEBEV");
        assertThat(r.children().get(0).ratioFrom()).isEqualTo(4);
    }

    @Test
    void noChildNamedIsReportedAndTheParentOwnListingIsNeverAChild() {
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02",
                "Demerger of Acme Industries Limited", null)));

        assertThat(r.children()).isEmpty();
        assertThat(r.missing()).containsExactly(SchemeNoticeReader.CHILD_NOT_NAMED);
    }

    @Test
    void aRegistrarOrOtherServiceProviderListedNearTheExDateIsNotAChild() {
        String text = "Scheme of arrangement of Acme Industries Limited: notice sent to Registrar and Transfer Agent Kfin Acme Services Limited, "
                + "to the depository and to the stock exchanges.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", text, null)),
                listing("KFINACME", "Kfin Acme Services Limited", EX.plusDays(20)));

        assertThat(r.children()).isEmpty();
    }

    @Test
    void aCompanyNamedOutsideAnySchemeContextIsNotAChild() {
        String text = "Demerger record date of Acme Industries Limited fixed. " + "x".repeat(700) + " Annual general meeting at the offices of Acme Foods Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", text, null)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.children()).isEmpty();
    }

    @Test
    void aNameWithAnApostropheMatchesBothWritings() {
        String text = "Demerger of Acme Industries Limited into Acme Wall's (India) Limited: 1 (One) equity share of Acme Wall\u2019s (India) Limited "
                + "for every 1 (One) equity share of Acme Industries Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", text, null)),
                listing("ACMEWALLS", "Acme Walls (India) Limited", EX.plusDays(20)));

        assertThat(r.children()).hasSize(1);
        assertThat(r.children().get(0).ratioFrom()).isEqualTo(1);
    }

    @Test
    void aFormerNameFromTheExchangeRenameListFindsTheChildAndItsRatio() {
        // the child trades as "Tata Motors Limited" now; the notices call it by the name it had at the time
        String pdf = "The Company has fixed Tuesday, October 14, 2025, as the \u201cRecord Date\u201d for the demerger: shareholders shall be issued "
                + "and allotted 1 (one) share of TML Commercial Vehicles Limited for every 1 (one) share held in Tata Motors Passenger Vehicles Limited.";
        LocalDate ex = LocalDate.of(2025, 10, 14);
        Listing child = new Listing("TMCV", "Tata Motors Limited", ex.plusDays(29), "INE000CHILD");
        Listing parent = new Listing("TMPV", "Tata Motors Passenger Vehicles Limited", LocalDate.of(1995, 1, 1), "INE000PARENT");
        List<NoticeText> notices = List.of(notice("https://x.example/rd.pdf", "2025-10-09", "Demerger record date", pdf));

        Reading without = SchemeNoticeReader.read(Kind.demerger, "Tata Motors Passenger Vehicles Limited", "TMPV", "INE000PARENT", ex, notices,
                List.of(child, parent));
        Reading with = SchemeNoticeReader.read(Kind.demerger, "Tata Motors Passenger Vehicles Limited", "TMPV", "INE000PARENT", ex, notices,
                List.of(child, parent), List.of(new SchemeNoticeReader.NameChange("TMCV", "TML Commercial Vehicles Limited", "Tata Motors Limited")));

        assertThat(without.children()).isEmpty();
        assertThat(with.children()).hasSize(1);
        assertThat(with.children().get(0).listing().symbol()).isEqualTo("TMCV");
        assertThat(with.children().get(0).ratioFrom()).isEqualTo(1);
        assertThat(with.children().get(0).ratioTo()).isEqualTo(1);
    }

    @Test
    void aMergersAcquirerNamedByItsFormerNameIsTheListingItBecame() {
        // Gallantt Ispat merged into Gallantt Metal, which was renamed Gallantt Ispat Limited (symbol GALLANTT) in 2022
        String pdf = "SUB: Fixation of Record Date for the purpose of Allotment of Shares by Gallantt Metal Limited to the Shareholders of Gallantt "
                + "Ispat Limited upon its amalgamation with Gallantt Metal Limited as per the Scheme of Amalgamation. Gallantt Metal Limited will "
                + "issue and allot 13 (Thirteen) fully paid-up equity shares of Rs. 10/- each of Gallantt Metal Limited, for every 14 (Fourteen) "
                + "equity shares of Re. 1/- each held by such member in the Company.";
        List<Listing> listings = List.of(new Listing("GALLANTT", "Gallantt Ispat Limited", LocalDate.of(2006, 4, 4), "INE297H01019"),
                new Listing("GALLISPAT", "Gallantt Ispat Limited", LocalDate.of(2006, 4, 4), "INE000OLD"));
        Reading r = SchemeNoticeReader.read(Kind.merger, "Gallantt Ispat Limited", "GALLISPAT", "INE000OLD", null,
                List.of(notice("https://x.example/rd.pdf", "2022-05-26", "Record date for the purpose of Merger/HiveOff", pdf)), listings,
                List.of(new SchemeNoticeReader.NameChange("GALLANTT", "Gallantt Metal Limited", "Gallantt Ispat Limited")));

        assertThat(r.children()).hasSize(1);
        assertThat(r.children().get(0).listing().symbol()).isEqualTo("GALLANTT");
        assertThat(r.children().get(0).ratioFrom()).isEqualTo(14);
        assertThat(r.children().get(0).ratioTo()).isEqualTo(13);
    }

    // ---- merger

    @Test
    void aMergersAcquirerAndRatioAreReadFromTheRecordDateNotice() {
        // wording of a record-date notice for an amalgamation
        String pdf = "SUB: Fixation of Record Date for the purpose of Allotment of Shares by Gallantt Metal Limited to the Shareholders of Gallantt "
                + "Ispat Limited in terms of Scheme of Amalgamation. NOTICE is hereby given that the Board has fixed 7th June, 2022 as Record Date "
                + "for the purpose of allotment of shares to the shareholders of Gallantt Ispat Limited upon its amalgamation with Gallantt Metal "
                + "Limited as per the Scheme of Amalgamation. In accordance with the Scheme, Gallantt Metal Limited will issue and allot to those "
                + "shareholders of the Company, 13 (Thirteen) fully paid-up equity shares of Rs. 10/- (Rupees Ten) each of Gallantt Metal Limited, "
                + "for every 14 (Fourteen) equity shares of the paid-up value of Re. 1/- (Rupee One) each held by such member in the Company.";
        Reading r = SchemeNoticeReader.read(Kind.merger, "Gallantt Ispat Limited", "GALLISPAT", null, null,
                List.of(notice("https://x.example/rd.pdf", "2022-05-26", "Record date for the purpose of Merger/HiveOff", pdf)),
                List.of(new Listing("GMLX", "Gallantt Metal Limited", LocalDate.of(2010, 1, 1), "INE000MERGE"),
                        new Listing("GALLISPAT", "Gallantt Ispat Limited", LocalDate.of(2006, 4, 4), "INE000PARENT")));

        assertThat(r.children()).hasSize(1);
        Child c = r.children().get(0);
        assertThat(c.listing().symbol()).isEqualTo("GMLX");
        assertThat(c.ratioFrom()).isEqualTo(14);
        assertThat(c.ratioTo()).isEqualTo(13);
        assertThat(r.recordDate()).isEqualTo(LocalDate.of(2022, 6, 7));
        assertThat(r.parentCostPct()).isNull();
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void aMergerWithNoListedAcquirerNamedIsReported() {
        Reading r = SchemeNoticeReader.read(Kind.merger, "Gone Limited", "GONE", null, null,
                List.of(notice("https://x.example/n.pdf", "2022-05-26", "Amalgamation of Gone Limited with Some Unlisted Private Limited", null)),
                List.of(new Listing("GONE", "Gone Limited", LocalDate.of(2000, 1, 1), null)));

        assertThat(r.children()).isEmpty();
        assertThat(r.missing()).contains(SchemeNoticeReader.CHILD_NOT_NAMED);
    }

    // ---- what must be rejected

    @Test
    void aBonusSentenceIsNeverTheRatio() {
        String pdf = "Demerger of Acme Industries Limited into Acme Foods Limited. The Board also approved a bonus issue of 1 (One) equity share of "
                + "Acme Industries Limited for every 2 (Two) equity shares held.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", "Demerger", pdf)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.children()).hasSize(1);
        assertThat(r.children().get(0).ratioFrom()).isNull();
        assertThat(r.missing()).contains(SchemeNoticeReader.RATIO_NOT_FOUND);
    }

    @Test
    void rightsSplitDividendAndEsopSentencesAreIgnoredToo() {
        for (String other : List.of("rights issue of 1 equity share for every 4 equity shares held", "split of 1 equity share into 5 equity shares, "
                + "1 share for every 5 shares", "dividend of 1 equity share for every 3 equity shares", "ESOP grant of 1 equity share for every 8 equity shares")) {
            Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02",
                            "Demerger of Acme Industries Limited into Acme Foods Limited", "The company announced a " + other + ".")),
                    listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));
            assertThat(r.children().get(0).ratioFrom()).as(other).isNull();
        }
    }

    @Test
    void aGarbageNumberIsNotARatio() {
        String pdf = "Demerger of Acme Industries Limited into Acme Foods Limited: 73454338 equity shares of Acme Foods Limited for every 3 equity "
                + "shares of Acme Industries Limited, and 1 equity share of Acme Foods Limited for every 5000 equity shares.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", "Demerger", pdf)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.children().get(0).ratioFrom()).isNull();
        assertThat(r.missing()).contains(SchemeNoticeReader.RATIO_NOT_FOUND);
    }

    @Test
    void noticesThatStateDifferentRatiosLeaveItBlankWithTheReason() {
        String a = "Demerger: 1 (One) equity share of Acme Foods Limited for every 5 (Five) equity shares of Acme Industries Limited.";
        String b = "Revised demerger: 1 (One) equity share of Acme Foods Limited for every 4 (Four) equity shares of Acme Industries Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/a.pdf", "2024-06-01", "Scheme", a),
                        notice("https://x.example/b.pdf", "2024-12-01", "Scheme", b)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.children().get(0).ratioFrom()).isNull();
        assertThat(r.missing()).contains(SchemeNoticeReader.NOTICES_DISAGREE).doesNotContain(SchemeNoticeReader.RATIO_NOT_FOUND);
    }

    @Test
    void noticesThatStateTheSameRatioInDifferentWordsAgree() {
        String a = "Demerger: 1 (One) equity share of Acme Foods Limited for every 5 (Five) equity shares of Acme Industries Limited.";
        String b = "Demerger record date: 2 (Two) equity shares of Acme Foods Limited for every 10 (Ten) equity shares of Acme Industries Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/a.pdf", "2024-06-01", "Scheme", a),
                        notice("https://x.example/b.pdf", "2024-12-01", "Scheme", b)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.children().get(0).ratioFrom()).isEqualTo(10); // the later notice's wording
        assertThat(r.children().get(0).ratioTo()).isEqualTo(2);
    }

    @Test
    void unpairedPercentagesAreNotACostSplit() {
        String pdf = "Demerger of Acme Industries Limited into Acme Foods Limited. Cost of acquisition: the cost of acquisition of the shares of Acme Industries Limited is apportioned: Acme Industries Limited "
                + "74.42% and Acme Foods Limited 74.42% of the original cost.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", "Cost", pdf)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.parentCostPct()).isNull();
        assertThat(r.children().get(0).costPct()).isNull();
        assertThat(r.missing()).contains(SchemeNoticeReader.COST_UNCLEAR);
    }

    @Test
    void aSplitThatDoesNotAddUpTo100IsRejectedAndOneThatDoesWithinHalfAPointIsKept() {
        String bad = "Demerger of Acme Industries Limited into Acme Foods Limited. Cost of acquisition: Acme Industries Limited 86.49% Acme Foods Limited 12.00% Total 98.49%";
        assertThat(demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", "Cost", bad)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20))).parentCostPct()).isNull();

        String ok = "Demerger of Acme Industries Limited into Acme Foods Limited. Cost of acquisition: Acme Industries Limited 86.49% Acme Foods Limited 13.20% Total 99.69%";
        assertThat(demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", "Cost", ok)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20))).parentCostPct()).isEqualByComparingTo(new BigDecimal("86.49"));
    }

    @Test
    void percentagesInANoticeThatDoesNotSpeakOfCostAreIgnored() {
        String pdf = "Shareholding pattern: Acme Industries Limited promoters 60.00% public 40.00%. Acme Foods Limited will be listed. "
                + "1 (One) equity share of Acme Foods Limited for every 5 (Five) equity shares of Acme Industries Limited.";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/n.pdf", "2025-01-02", "Demerger", pdf)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.parentCostPct()).isNull();
        assertThat(r.missing()).containsExactly(SchemeNoticeReader.COST_NOT_PUBLISHED_YET);
        assertThat(r.children().get(0).ratioFrom()).isEqualTo(5);
    }

    @Test
    void theLatestNoticeThatStatesASplitWins() {
        String early = "Demerger into Acme Foods Limited. Draft cost of acquisition: Acme Industries Limited 60.00% Acme Foods Limited 40.00% Total 100.00%";
        String late = "Demerger into Acme Foods Limited. Final cost of acquisition: Acme Industries Limited 80.00% Acme Foods Limited 20.00% Total 100.00%";
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(notice("https://x.example/late.pdf", "2025-02-01", "Cost", late),
                        notice("https://x.example/early.pdf", "2024-12-01", "Cost", early)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.parentCostPct()).isEqualByComparingTo("80.00");
        assertThat(r.costSource().url()).isEqualTo("https://x.example/late.pdf");
    }

    // ---- bookkeeping

    @Test
    void aScannedNoticeIsReportedWhenNothingElseGaveTheRatio() {
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(
                        notice("https://x.example/n.pdf", "2025-01-02", "Demerger of Acme Industries Limited into Acme Foods Limited", null),
                        new NoticeText("https://x.example/scan.pdf", LocalDate.of(2025, 1, 3), "Record date", "", true)),
                listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.missing()).contains(SchemeNoticeReader.NOTICE_SCANNED, SchemeNoticeReader.RATIO_NOT_FOUND);
    }

    @Test
    void noNoticesIsReportedAsSuch() {
        Reading r = demerger("Acme Industries Limited", "ACME", EX, List.of(), listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(20)));

        assertThat(r.missing()).contains(SchemeNoticeReader.NO_NOTICES, SchemeNoticeReader.CHILD_NOT_NAMED);
    }

    @Test
    void recordDatesInTheUsualWritings() {
        assertThat(SchemeNoticeReader.parseDate("07-Jun-2022")).isEqualTo(LocalDate.of(2022, 6, 7));
        assertThat(SchemeNoticeReader.parseDate("7th June, 2022")).isEqualTo(LocalDate.of(2022, 6, 7));
        assertThat(SchemeNoticeReader.parseDate("June 7, 2022")).isEqualTo(LocalDate.of(2022, 6, 7));
        assertThat(SchemeNoticeReader.parseDate("07/06/2022")).isEqualTo(LocalDate.of(2022, 6, 7));
        assertThat(SchemeNoticeReader.parseDate("tomorrow at nine")).isNull();
        assertThat(SchemeNoticeReader.parseDate("31-Feb-2022")).isNull();
    }

    // ---- a merger's record date comes only from a sentence about the scheme

    private static Reading mergerRead(String pdf) {
        return SchemeNoticeReader.read(Kind.merger, "Gallantt Ispat Limited", "GALLISPAT", null, null,
                List.of(notice("https://x.example/rd.pdf", "2022-05-26", "Amalgamation of Gallantt Ispat Limited with Gallantt Metal Limited", pdf)),
                List.of(new Listing("GMLX", "Gallantt Metal Limited", LocalDate.of(2010, 1, 1), "INE000MERGE"),
                        new Listing("GALLISPAT", "Gallantt Ispat Limited", LocalDate.of(2006, 4, 4), "INE000PARENT")));
    }

    private static final String RATIO_SENTENCE = " In accordance with the Scheme of Amalgamation, Gallantt Metal Limited will issue and allot to the "
            + "shareholders of Gallantt Ispat Limited 13 (Thirteen) fully paid-up equity shares for every 14 (Fourteen) equity shares held.";

    @Test
    void anEvotingRecordDateIsNeverTheMergersRecordDate() {
        String pdf = "The cut-off date and record date for remote e-voting on the resolution to approve the Scheme of Amalgamation is 20th May, 2022."
                + RATIO_SENTENCE;

        Reading r = mergerRead(pdf);

        assertThat(r.recordDate()).isNull();
        assertThat(r.missing()).contains(SchemeNoticeReader.RECORD_DATE_NOT_FOUND);
    }

    @Test
    void aMeetingOrDividendOrAgmRecordDateIsIgnored() {
        for (String sentence : new String[] {
                "The record date for the meeting of the equity shareholders to consider the Scheme of Amalgamation is 20th May, 2022.",
                "The record date for the final dividend is 20th May, 2022.",
                "The record date for the Annual General Meeting is 20th May, 2022."}) {
            Reading r = mergerRead(sentence + RATIO_SENTENCE);

            assertThat(r.recordDate()).as(sentence).isNull();
        }
    }

    @Test
    void theSchemeRecordDateIsTakenWhenAnEvotingOneComesFirst() {
        String pdf = "The record date for remote e-voting is 20th May, 2022. "
                + "The Board has fixed 7th June, 2022 as Record Date for the purpose of allotment of shares under the Scheme of Amalgamation."
                + RATIO_SENTENCE;

        Reading r = mergerRead(pdf);

        assertThat(r.recordDate()).isEqualTo(LocalDate.of(2022, 6, 7));
        assertThat(r.missing()).doesNotContain(SchemeNoticeReader.RECORD_DATE_NOT_FOUND);
    }
}
