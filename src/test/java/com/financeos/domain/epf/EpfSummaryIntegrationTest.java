package com.financeos.domain.epf;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.core.time.AppTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /epf/summary and /epf/employers over real persistence, with the business date fixed at 2026-10-15 (FY 2026-27). */
@SpringBootTest
@AutoConfigureMockMvc
class EpfSummaryIntegrationTest extends EpfIntegrationBase {

    private JsonNode summary() throws Exception {
        return api.getJson(BASE + "/summary", 200);
    }

    @Test
    void aUserWithNothingHasNoData() throws Exception {
        JsonNode s = summary();
        assertFalse(s.get("hasData").asBoolean());
        assertAmount("0", s.get("balance"));
        assertTrue(s.get("interestEstimate").isNull());
        assertTrue(s.get("lastInterest").isNull());
        assertTrue(s.get("pension").isNull());
        assertTrue(s.get("staleThrough").isNull());
        assertTrue(s.get("uanLast4").isNull());
    }

    @Test
    void balanceFyContributionsLastInterestAndTheInterestEstimate() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        addEntry(body("OPENING_BALANCE", e1, "2026-03-31", 100000, 50000));
        addEntry(interest(e1, 2025, "2026-03-31", 6000, 3000));
        for (int m = 4; m <= 9; m++) { // wage months Apr..Sep 2026, credited on the 10th of the next month
            addEntry(contribution(e1, "2026-%02d-01".formatted(m), "2026-%02d-10".formatted(m + 1), 3000, 1750, 1250));
        }
        jdbc.update("UPDATE epf_employer SET uan = ? WHERE id = ?", "100200300456", e1.toString());

        JsonNode s = summary();

        assertTrue(s.get("hasData").asBoolean());
        assertAmount("187500", s.get("balance")); // 159000 + 6 x 4750
        assertAmount("18000", s.get("fyEmployee"));
        assertAmount("10500", s.get("fyEmployer"));
        assertEquals("0456", s.get("uanLast4").asText());
        JsonNode last = s.get("lastInterest");
        assertEquals(2025, last.get("fy").asInt());
        assertAmount("9000", last.get("amount"));
        assertAmount("8.25", last.get("rate"));
        assertEquals("2026-03-31", last.get("creditedOn").asText());
        // FY 2026 has no declared rate yet: the latest declared (FY 2025, 8.25%) applies, on the month-end balances Apr..Sep.
        JsonNode estimate = s.get("interestEstimate");
        assertEquals(2026, estimate.get("fy").asInt());
        assertEquals(2025, estimate.get("rateFy").asInt());
        assertAmount("8.25", estimate.get("rate"));
        assertAmount("7049", estimate.get("amount")); // 1,025,250 x 8.25 / 1200 = 7048.59
        assertTrue(s.get("staleThrough").isNull());
    }

    @Test
    void noEstimateOnceThisYearsInterestIsCreditedOrWhenTheBalanceIsZero() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        assertTrue(summary().get("interestEstimate").isNull()); // employer without any entries: balance 0

        addEntry(body("OPENING_BALANCE", e1, "2026-03-31", 1000, 0));
        assertFalse(summary().get("interestEstimate").isNull());

        addEntry(interest(e1, 2026, "2026-09-01", 10, 0));
        assertTrue(summary().get("interestEstimate").isNull());
    }

    @Test
    void aZeroInterestEntryIsEpfosPlaceholderNotACredit() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        addEntry(body("OPENING_BALANCE", e1, "2026-03-31", 1000, 0));
        addEntry(interest(e1, 2026, "2027-03-31", 0, 0));

        JsonNode s = summary();
        assertFalse(s.get("interestEstimate").isNull());
        assertTrue(s.get("lastInterest").isNull());
    }

    @Test
    void noEstimateInAprilBecauseNoMonthOfTheYearHasClosed() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        addEntry(body("OPENING_BALANCE", e1, "2026-03-31", 100000, 0));
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 4, 20).atTime(10, 0).atZone(ist).toInstant(), ist));
        assertTrue(summary().get("interestEstimate").isNull());

        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 5, 2).atTime(10, 0).atZone(ist).toInstant(), ist));
        assertAmount("688", summary().get("interestEstimate").get("amount")); // April month-end 100000 x 8.25 / 1200 = 687.5
    }

    @Test
    void estimateUsesTheDeclaredRateOfTheCurrentYearWhenThereIsOne() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        addEntry(body("OPENING_BALANCE", e1, "2024-03-31", 120000, 0));
        // Fix the date inside FY 2024 (declared 8.25) for the estimate: Apr..Jun month-ends = 3 x 120000.
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        AppTime.useClock(Clock.fixed(LocalDate.of(2024, 7, 15).atTime(10, 0).atZone(ist).toInstant(), ist));

        JsonNode estimate = summary().get("interestEstimate");

        assertEquals(2024, estimate.get("fy").asInt());
        assertEquals(2024, estimate.get("rateFy").asInt());
        assertAmount("2475", estimate.get("amount")); // 360,000 x 8.25 / 1200
    }

    @Test
    void pensionServiceIsRoundedAndSumsEmployersExceptThoseWhosePensionWasPaidOut() throws Exception {
        UUID current = employer("Acme", "2010-04-01", null); // to 2026-10-15: 198 months
        UUID left = employer("Beta", "2012-04-01", "2015-03-31"); // 36 months
        addEntry(contribution(current, "2026-09-01", "2026-10-10", 3000, 1750, 1000));
        addEntry(body("OPENING_BALANCE", left, "2015-03-31", 1000, 500));

        JsonNode withBoth = summary().get("pension");
        assertAmount("1000", withBoth.get("monthlyContribution"));
        assertEquals(234, withBoth.get("serviceMonths").asInt());
        assertTrue(withBoth.get("eligible").asBoolean());
        // 234 months = 19.5 years rounds up to 20; wage = 1000 / 0.0833 = 12004.8019 -> x 20 / 70 = 3429.9
        assertAmount("3430", withBoth.get("estimatedMonthlyPension"));

        addEntry(withdrawal(left, "PENSION", "2015-06-01", 0, 0, 800, null));
        JsonNode paidOut = summary().get("pension");
        assertEquals(198, paidOut.get("serviceMonths").asInt());
        // 198 months = 16.5 years -> 17: 12004.8019 x 17 / 70 = 2915.5
        assertAmount("2915", paidOut.get("estimatedMonthlyPension"));
    }

    @Test
    void pensionWageIsCappedAtFifteenThousandAndEligibilityNeedsTenRoundedYears() throws Exception {
        UUID current = employer("Acme", "2020-04-01", null); // 78 months -> 7 years
        addEntry(contribution(current, "2026-09-01", "2026-10-10", 9000, 9000, 1250));

        JsonNode pension = summary().get("pension");

        assertEquals(78, pension.get("serviceMonths").asInt());
        assertFalse(pension.get("eligible").asBoolean());
        assertAmount("1500", pension.get("estimatedMonthlyPension")); // 15000 x 7 / 70
    }

    @Test
    void anEmployerWithoutDatesServesUntilTheMonthAfterItsLastContributionOnceItIsNotCurrent() throws Exception {
        UUID old = employer("Old Co", null, null);
        addEntry(contribution(old, "2019-04-01", "2019-05-10", 100, 100, 100));
        addEntry(contribution(old, "2020-03-01", "2020-04-10", 100, 100, 100));
        addEntry(withdrawal(old, "FINAL_SETTLEMENT", "2020-06-01", 400, 400, 0, null)); // SETTLED, no left date

        JsonNode pension = summary().get("pension");

        assertEquals(12, pension.get("serviceMonths").asInt()); // Apr 2019 until the month after Mar 2020
    }

    @Test
    void staleThroughIsTheWageMonthOfTheLatestContributionOfAQuietCurrentEmployer() throws Exception {
        UUID current = employer("Acme", "2020-04-01", null);
        UUID left = employer("Old Co", "2015-04-01", "2016-03-31");
        addEntry(contribution(current, "2026-05-01", "2026-06-01", 100, 100, 0)); // 136 days ago
        addEntry(contribution(left, "2016-02-01", "2016-03-10", 100, 100, 0)); // left employers never go stale

        assertEquals("2026-05-01", summary().get("staleThrough").asText());

        addEntry(contribution(current, "2026-09-01", "2026-09-10", 100, 100, 0)); // 35 days ago: fresh
        assertTrue(summary().get("staleThrough").isNull());
    }

    @Test
    void employerStatusIsSettledTransferredLeftOrCurrentAndCurrentComesFirst() throws Exception {
        UUID current = employer("Current Co", "2024-01-01", null);
        UUID left = employer("Left Co", "2019-01-01", "2021-12-31");
        UUID transferred = employer("Transferred Co", "2015-01-01", "2018-12-31");
        UUID partiallyTransferred = employer("Part Co", "2012-01-01", null);
        UUID settled = employer("Settled Co", "2010-01-01", "2011-12-31");

        addEntry(body("OPENING_BALANCE", transferred, "2018-12-31", 1000, 500));
        Map<String, Object> in = body("TRANSFER_IN", left, "2019-01-05", 1000, 500);
        in.put("fromEmployerId", transferred.toString());
        addEntry(in); // moves everything out of "transferred" into "left"
        addEntry(body("OPENING_BALANCE", partiallyTransferred, "2016-01-01", 1000, 0));
        Map<String, Object> partial = body("TRANSFER_IN", current, "2024-01-05", 400, 0);
        partial.put("fromEmployerId", partiallyTransferred.toString());
        addEntry(partial); // 600 left behind
        addEntry(body("OPENING_BALANCE", settled, "2011-12-31", 700, 300));
        addEntry(withdrawal(settled, "FINAL_SETTLEMENT", "2012-02-10", 700, 300, 0, null));

        JsonNode employers = api.getJson(BASE + "/employers", 200);

        assertEquals("Current Co", employers.get(0).get("name").asText());
        for (JsonNode e : employers) {
            String name = e.get("name").asText();
            switch (name) {
                case "Current Co" -> assertEquals("CURRENT", e.get("status").asText());
                case "Left Co" -> assertEquals("LEFT", e.get("status").asText());
                case "Transferred Co" -> {
                    assertEquals("TRANSFERRED", e.get("status").asText());
                    assertEquals("2019-01-05", e.get("statusDate").asText());
                    assertAmount("0", e.get("balance"));
                }
                case "Part Co" -> {
                    assertEquals("CURRENT", e.get("status").asText()); // transferred out but 600 remains, and no left date
                    assertAmount("600", e.get("balance"));
                }
                case "Settled Co" -> {
                    assertEquals("SETTLED", e.get("status").asText());
                    assertEquals("2012-02-10", e.get("statusDate").asText());
                }
                default -> throw new AssertionError(name);
            }
        }
        // CURRENT employers first (newest joined first), then the rest newest first.
        assertEquals("Current Co", employers.get(0).get("name").asText());
        assertEquals("Part Co", employers.get(1).get("name").asText());
        assertEquals("Left Co", employers.get(2).get("name").asText());
        assertEquals("Transferred Co", employers.get(3).get("name").asText());
        assertEquals("Settled Co", employers.get(4).get("name").asText());
    }

    @Test
    void employerRulesNameMemberIdAndDates() throws Exception {
        UUID acme = employer(api, "  Acme  ", "KDBNG0012345000", "2020-04-01", null);
        assertEquals("Acme", api.getJson(BASE + "/employers", 200).get(0).get("name").asText());

        assertEquals("Another employer already uses this member ID.",
                message(api.postJson(BASE + "/employers", Map.of("name", "Dup", "memberId", "KDBNG0012345000"), 400)));
        // another user may use the same member id
        employer(otherApi, "Other", "KDBNG0012345000", null, null);
        // updating an employer keeps its own member id
        JsonNode updated = api.putJson(BASE + "/employers/" + acme,
                Map.of("name", "Acme Ltd", "memberId", "KDBNG0012345000", "joinedOn", "2020-04-01"), 200);
        assertEquals("Acme Ltd", updated.get("name").asText());
        api.postJson(BASE + "/employers", Map.of("name", "   "), 400);
        api.postJson(BASE + "/employers", Map.of("name", "Bad", "joinedOn", "2021-01-01", "leftOn", "2020-01-01"), 400);
    }

    @Test
    void anotherUsersEmployerIsNotFound() throws Exception {
        UUID mine = employer("Mine", null, null);
        otherApi.putJson(BASE + "/employers/" + mine, Map.of("name", "Hijack"), 404);
        assertEquals(0, otherApi.getJson(BASE + "/employers", 200).size());
        assertFalse(otherApi.getJson(BASE + "/summary", 200).get("hasData").asBoolean());
    }

    @Test
    void fyContributionTotalsFollowTheDueMonthOfALatePostedContribution() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        // Both credited late on the same day: Feb wage is due March (FY 2025), Mar wage is due April (FY 2026).
        addEntry(contribution(e1, "2026-02-01", "2026-09-17", 1000, 500, 0));
        addEntry(contribution(e1, "2026-03-01", "2026-09-17", 3000, 1750, 0));

        JsonNode s = summary();

        assertAmount("3000", s.get("fyEmployee"));
        assertAmount("1750", s.get("fyEmployer"));
    }

    // ---- filters: employerId, fy, allYears

    private JsonNode summary(String query) throws Exception {
        return api.getJson(BASE + "/summary" + query, 200);
    }

    /** Acme: opening 100000/50000 at 2024-03-31, FY 2024 interest 6000/3000, contributions Apr..Jun 2025 and Apr..Sep 2026. Beta: opening 1000/0. */
    private UUID[] filterFixture() throws Exception {
        UUID acme = employer("Acme", "2020-04-01", null);
        UUID beta = employer("Beta", "2020-04-01", null);
        addEntry(body("OPENING_BALANCE", acme, "2024-03-31", 100000, 50000));
        addEntry(interest(acme, 2024, "2025-03-31", 6000, 3000));
        for (int m = 4; m <= 6; m++) { // due May..Jul 2025: FY 2025
            addEntry(contribution(acme, "2025-%02d-01".formatted(m), "2025-%02d-10".formatted(m + 1), 3000, 1750, 1250));
        }
        for (int m = 4; m <= 9; m++) { // due May..Oct 2026: FY 2026
            addEntry(contribution(acme, "2026-%02d-01".formatted(m), "2026-%02d-10".formatted(m + 1), 3000, 1750, 1250));
        }
        addEntry(body("OPENING_BALANCE", beta, "2026-03-31", 1000, 0));
        addEntry(interest(beta, 2025, "2026-03-31", 80, 0));
        return new UUID[]{acme, beta};
    }

    @Test
    void noParamsKeepsTodaysAllEmployersCurrentFyFigures() throws Exception {
        filterFixture();
        JsonNode s = summary();
        assertAmount("18000", s.get("fyEmployee"));
        assertAmount("10500", s.get("fyEmployer"));
        assertTrue(s.get("balanceAsOf").isNull());
        assertEquals(2026, s.get("periodFy").asInt());
        assertFalse(s.get("interestEstimate").isNull());
        assertTrue(s.get("periodInterest").isNull()); // nothing credited for FY 2026
        assertEquals(s.toString(), summary("?allYears=false").toString());
    }

    @Test
    void anEmployerScopeLimitsBalanceContributionsLastInterestAndEstimate() throws Exception {
        UUID[] ids = filterFixture();
        JsonNode all = summary();

        JsonNode beta = summary("?employerId=" + ids[1]);
        assertAmount("1080", beta.get("balance"));
        assertAmount("0", beta.get("fyEmployee"));
        assertEquals(2025, beta.get("lastInterest").get("fy").asInt());
        assertAmount("80", beta.get("lastInterest").get("amount"));
        assertAmount("8.25", beta.get("interestEstimate").get("rate"));
        assertAmount("45", beta.get("interestEstimate").get("amount")); // 6 x 1080 x 8.25 / 1200 = 44.55
        assertEquals(all.get("pension").toString(), beta.get("pension").toString());
        assertEquals(all.get("throughMonth").asText(), beta.get("throughMonth").asText());

        JsonNode acme = summary("?employerId=" + ids[0]);
        assertAmount("18000", acme.get("fyEmployee"));
        assertEquals(2024, acme.get("lastInterest").get("fy").asInt());
    }

    @Test
    void aPastFyUsesTheBalanceAtThirtyFirstMarchAndThatYearsContributionsAndInterest() throws Exception {
        filterFixture();

        JsonNode s = summary("?fy=2024");
        assertAmount("159000", s.get("balance")); // acme at 2025-03-31 incl. interest; beta has no entries yet
        assertEquals("2025-03-31", s.get("balanceAsOf").asText());
        assertEquals(2024, s.get("periodFy").asInt());
        assertAmount("0", s.get("fyEmployee"));
        assertTrue(s.get("interestEstimate").isNull());
        JsonNode period = s.get("periodInterest");
        assertEquals(2024, period.get("fy").asInt());
        assertAmount("9000", period.get("amount"));
        assertAmount("8.25", period.get("rate"));
        assertEquals("2025-03-31", period.get("creditedOn").asText());

        JsonNode fy2025 = summary("?fy=2025");
        assertAmount("9000", fy2025.get("fyEmployee")); // 3 x 3000 by due month May..Jul 2025
        assertAmount("5250", fy2025.get("fyEmployer"));
        assertEquals("2026-03-31", fy2025.get("balanceAsOf").asText());
        assertAmount("80", fy2025.get("periodInterest").get("amount"));
        assertTrue(fy2025.get("interestEstimate").isNull());
    }

    @Test
    void allYearsSumsEveryContributionAndCreditedInterestWithTodaysBalance() throws Exception {
        filterFixture();

        JsonNode s = summary("?allYears=true&fy=2024");
        assertTrue(s.get("periodFy").isNull());
        assertTrue(s.get("balanceAsOf").isNull());
        assertAmount(summary().get("balance").asText(), s.get("balance"));
        assertAmount("27000", s.get("fyEmployee")); // 3 + 6 months x 3000
        assertAmount("15750", s.get("fyEmployer"));
        assertTrue(s.get("interestEstimate").isNull());
        JsonNode period = s.get("periodInterest");
        assertTrue(period.get("fy").isNull());
        assertTrue(period.get("rate").isNull());
        assertAmount("9080", period.get("amount"));
        assertEquals("2026-03-31", period.get("creditedOn").asText());
    }

    @Test
    void currentFyWithInterestAlreadyCreditedShowsItAndNoEstimate() throws Exception {
        UUID[] ids = filterFixture();
        addEntry(interest(ids[1], 2026, "2026-09-01", 10, 0));

        JsonNode s = summary();
        assertTrue(s.get("interestEstimate").isNull());
        assertEquals(2026, s.get("periodInterest").get("fy").asInt());
        assertAmount("10", s.get("periodInterest").get("amount"));
        assertTrue(summary("?employerId=" + ids[0]).get("periodInterest").isNull());
    }

    @Test
    void aZeroPlaceholderInterestNeverCountsAsPeriodInterest() throws Exception {
        UUID e1 = employer("Acme", "2020-04-01", null);
        addEntry(body("OPENING_BALANCE", e1, "2026-03-31", 1000, 0));
        addEntry(interest(e1, 2026, "2027-03-31", 0, 0));

        assertTrue(summary().get("periodInterest").isNull());
        assertTrue(summary("?allYears=true").get("periodInterest").isNull());
    }

    @Test
    void aForeignOrUnknownEmployerIsNotFound() throws Exception {
        UUID mine = employer("Mine", null, null);
        otherApi.getJson(BASE + "/summary?employerId=" + mine, 404);
        api.getJson(BASE + "/summary?employerId=" + UUID.randomUUID(), 404);
    }

    @Test
    void aFutureFyKeepsTodaysBalanceWithoutAnEstimate() throws Exception {
        filterFixture();

        JsonNode s = summary("?fy=2030");
        assertAmount(summary().get("balance").asText(), s.get("balance"));
        assertTrue(s.get("balanceAsOf").isNull());
        assertEquals(2030, s.get("periodFy").asInt());
        assertTrue(s.get("interestEstimate").isNull());
        assertAmount("0", s.get("fyEmployee"));
    }
}
