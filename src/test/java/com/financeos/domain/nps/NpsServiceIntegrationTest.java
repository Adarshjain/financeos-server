package com.financeos.domain.nps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.price.NpsTrustFeedClient;
import com.financeos.domain.instrument.price.PriceHistoryBackfilledEvent;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NPS over real persistence: catalog sync, accounts and PRAN rules, tenancy, entries, allocation, the summary maths. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class NpsServiceIntegrationTest {

    private static final String BASE = "/api/v1/nps";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String EQ = "SMT99001";
    private static final String GOV = "SMT99002";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired NpsCatalogService catalogService;
    @Autowired NpsService npsService;
    @Autowired InstrumentPriceRepository priceRepository;
    @Autowired InstrumentRepository instrumentRepository;
    @Autowired NpsRepriceService repriceService;
    @Autowired ApplicationEvents events;
    @Autowired ApplicationEventPublisher publisher;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "nps-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String other = "nps-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, other);
        otherUserId = userRepository.findByEmail(other).orElseThrow().getId();
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        seedCatalog();
    }

    @AfterEach
    void cleanUp() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
        jdbc.update("DELETE FROM instrument_prices WHERE instrument_id IN "
                + "(SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT99%')");
    }

    @AfterAll
    static void dropCatalog(@Autowired JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM instrument_prices WHERE instrument_id IN (SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT99%')");
        List<String> instrumentIds = jdbc.queryForList("SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT99%'", String.class);
        jdbc.update("DELETE FROM nps_scheme WHERE scheme_code LIKE 'SMT99%'");
        instrumentIds.forEach(id -> jdbc.update("DELETE FROM instruments WHERE id = ?", id));
    }

    /** Two schemes (equity 100.0, government 50.0) with a NAV on the last two days; the sync is idempotent. */
    private void seedCatalog() {
        List<NpsTrustFeedClient.NavRow> rows = new ArrayList<>();
        for (LocalDate d : List.of(TODAY.minusDays(2), TODAY.minusDays(1))) {
            rows.add(new NpsTrustFeedClient.NavRow(EQ, "TEST PENSION FUND SCHEME E - TIER I DIRECT", "Test PFM", d,
                    d.equals(TODAY.minusDays(1)) ? new BigDecimal("100.0000") : new BigDecimal("98.0000")));
            rows.add(new NpsTrustFeedClient.NavRow(GOV, "TEST PENSION FUND SCHEME G - TIER I DIRECT", "Test PFM", d,
                    d.equals(TODAY.minusDays(1)) ? new BigDecimal("50.0000") : new BigDecimal("49.0000")));
        }
        // Per day: the sync keeps the first row of a scheme, so apply the older day first, then the newer.
        catalogService.apply(rows.subList(0, 2));
        catalogService.apply(rows.subList(2, 4));
    }

    // ------------------------------------------------------------------ builders

    private Map<String, Object> accountBody(String pran, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pran", pran);
        body.put("holderName", name);
        body.put("relation", "SELF");
        body.put("sector", "ALL_CITIZEN");
        body.put("cra", "PROTEAN");
        return body;
    }

    private UUID account(ApiTestClient client, String pran) throws Exception {
        return client.create(BASE + "/accounts", accountBody(pran, "Test Holder"));
    }

    private void allocate(UUID account, int eq, int gov) throws Exception {
        api.putJson(BASE + "/accounts/" + account + "/allocation", Map.of("tier", "I", "choice", "Active",
                "rows", List.of(Map.of("schemeCode", EQ, "percent", eq), Map.of("schemeCode", GOV, "percent", gov))), 200);
    }

    private Map<String, Object> entry(UUID account, String type, String contributor, Number amount, String date,
                                      List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("tier", "I");
        body.put("type", type);
        body.put("contributor", contributor);
        body.put("amount", amount);
        body.put("postedDate", date);
        body.put("lines", lines);
        return body;
    }

    private static Map<String, Object> line(String scheme, String units) {
        return Map.of("schemeCode", scheme, "units", new BigDecimal(units));
    }

    private static void assertAmount(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), "expected " + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void theSyncCreatesInstrumentsWithParsedMetaAndKeepsOneNavPerDay() throws Exception {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_scheme WHERE scheme_code = ?", Integer.class, EQ));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM instrument_prices p JOIN nps_scheme s ON s.instrument_id = p.instrument_id "
                + "WHERE s.scheme_code = ?", Integer.class, EQ));

        // the fund-house filter uses the code derived from the scheme id (SMT99001 -> PFMT99)
        assertEquals(2, api.getJson(BASE + "/schemes?pfm=pfmt99", 200).size());
        assertEquals(0, api.getJson(BASE + "/schemes?pfm=PFM008", 200).size());
        JsonNode found = api.getJson(BASE + "/schemes?query=" + EQ.toLowerCase(), 200);
        assertEquals(1, found.size());
        JsonNode s = found.get(0);
        assertEquals("E", s.get("assetClass").asText());
        assertEquals("I", s.get("tier").asText());
        assertEquals("DIRECT", s.get("variant").asText());
        assertAmount("100", s.get("nav"));
        assertEquals(TODAY.minusDays(1).toString(), s.get("navDate").asText());
        assertFalse(s.get("instrumentId").asText().isBlank());
        assertEquals(1, api.getJson(BASE + "/schemes?query=" + GOV + "&tier=I&variant=DIRECT", 200).size());
        assertEquals(0, api.getJson(BASE + "/schemes?query=" + GOV + "&variant=POP", 200).size());
        assertEquals(EQ, api.getJson(BASE + "/schemes/" + EQ, 200).get("scheme").get("schemeCode").asText());
        api.getJson(BASE + "/schemes/NOPE", 404);
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void aPranIsTwelveDigitsStoredMaskedAndUniquePerUser() throws Exception {
        api.postJson(BASE + "/accounts", accountBody("12345", "A"), 400);
        api.postJson(BASE + "/accounts", accountBody("12345678901x", "A"), 400);
        api.postJson(BASE + "/accounts", accountBody(null, "A"), 400);
        api.postJson(BASE + "/accounts", accountBody("123456789012", " "), 400);

        JsonNode created = api.postJson(BASE + "/accounts", accountBody("1234 5678 9012", "Asha"), 201);
        assertEquals("XXXX XXXX 9012", created.get("pranMasked").asText());
        assertEquals("Asha", created.get("holderName").asText());
        assertFalse(created.toString().contains("123456789012"));
        // the stored PRAN is encrypted, only the last four digits are readable
        String stored = jdbc.queryForObject("SELECT pran_encrypted FROM nps_account WHERE id = ?", String.class, created.get("id").asText());
        assertFalse(stored.contains("123456789012"));
        assertEquals("9012", jdbc.queryForObject("SELECT pran_last4 FROM nps_account WHERE id = ?", String.class, created.get("id").asText()));

        api.postJson(BASE + "/accounts", accountBody("123456789012", "Same again"), 409);
        api.postJson(BASE + "/accounts", accountBody("1234-5678-9012", "Same again"), 409);
        // another user may add the same number
        otherApi.postJson(BASE + "/accounts", accountBody("123456789012", "Other"), 201);

        UUID second = api.create(BASE + "/accounts", accountBody("999988887777", "Child"));
        api.putJson(BASE + "/accounts/" + second, Map.of("pran", "123456789012"), 409);
        JsonNode updated = api.putJson(BASE + "/accounts/" + second, Map.of("holderName", "Kid", "relation", "CHILD",
                "sector", "VATSALYA", "tier2Active", true), 200);
        assertEquals("Kid", updated.get("holderName").asText());
        assertEquals("CHILD", updated.get("relation").asText());
        assertEquals("XXXX XXXX 7777", updated.get("pranMasked").asText());
        assertEquals(2, api.getJson(BASE + "/summary", 200).get("accounts").size());

        api.deleteJson(BASE + "/accounts/" + second, 204);
        assertEquals(1, api.getJson(BASE + "/summary", 200).get("accounts").size());
    }

    @Test
    void anotherUsersAccountsAndEntriesAreNotFound() throws Exception {
        UUID theirs = account(otherApi, "111122223333");
        UUID theirEntry = UUID.fromString(otherApi.postJson(BASE + "/entries",
                entry(theirs, "OPENING", null, 1000, "2025-04-01", List.of(line(EQ, "10"))), 201).get("id").asText());

        api.putJson(BASE + "/accounts/" + theirs, Map.of("holderName", "Mine now"), 404);
        api.deleteJson(BASE + "/accounts/" + theirs, 404);
        api.putJson(BASE + "/accounts/" + theirs + "/allocation", Map.of("tier", "I",
                "rows", List.of(Map.of("schemeCode", EQ, "percent", 100))), 404);
        api.postJson(BASE + "/entries", entry(theirs, "OPENING", null, 1, "2025-04-01", List.of(line(EQ, "1"))), 404);
        api.getJson(BASE + "/entries/" + theirEntry, 404);
        api.putJson(BASE + "/entries/" + theirEntry, entry(theirs, "OPENING", null, 1, "2025-04-01", List.of(line(EQ, "1"))), 404);
        api.deleteJson(BASE + "/entries/" + theirEntry, 404);
        JsonNode mine = api.getJson(BASE + "/summary", 200);
        assertEquals(0, mine.get("accounts").size());
        assertEquals(0, mine.get("holdings").size());
        assertEquals(0, api.getJson(BASE + "/entries", 200).get("totalElements").asInt());
        assertEquals(1, otherApi.getJson(BASE + "/entries", 200).get("totalElements").asInt());
        // the scheme detail shows only the caller's own holding and entries
        assertEquals(0, api.getJson(BASE + "/schemes/" + EQ, 200).get("holdings").size());
        assertEquals(1, otherApi.getJson(BASE + "/schemes/" + EQ, 200).get("holdings").size());
    }

    // ------------------------------------------------------------------ allocation

    @Test
    void allocationMustSumToOneHundredOverKnownSchemes() throws Exception {
        UUID acc = account(api, "222233334444");
        String url = BASE + "/accounts/" + acc + "/allocation";
        api.putJson(url, Map.of("tier", "I", "rows", List.of(Map.of("schemeCode", EQ, "percent", 60),
                Map.of("schemeCode", GOV, "percent", 30))), 400);
        api.putJson(url, Map.of("tier", "I", "rows", List.of(Map.of("schemeCode", "NOPE", "percent", 100))), 400);
        api.putJson(url, Map.of("tier", "I", "rows", List.of(Map.of("schemeCode", EQ, "percent", 50),
                Map.of("schemeCode", EQ, "percent", 50))), 400);
        api.putJson(url, Map.of("tier", "I", "rows", List.of()), 400);

        allocate(acc, 70, 30);
        allocate(acc, 60, 40); // replaces the earlier rows of the tier
        JsonNode summary = api.getJson(BASE + "/summary", 200);
        assertEquals(2, summary.get("allocation").size());
        assertAmount("60", summary.get("allocation").get(0).get("percent"));
        assertEquals("Active", summary.get("accounts").get(0).get("schemeChoice").asText());
    }

    // ------------------------------------------------------------------ entries

    @Test
    void aContributionWithoutLinesIsSplitByAllocationAndEstimatedAtTheEarlierNav() throws Exception {
        UUID acc = account(api, "333344445555");
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 1000, TODAY.toString(), null), 400); // no allocation yet
        allocate(acc, 60, 40);

        // posted today: nearest earlier NAV is yesterday's (100.0 and 50.0)
        JsonNode e = api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 1000, TODAY.toString(), null), 201);

        assertEquals("ESTIMATED", e.get("status").asText());
        assertEquals(TODAY.minusDays(1).toString(), e.get("navDate").asText());
        assertEquals(2, e.get("lines").size());
        for (JsonNode l : e.get("lines")) {
            if (EQ.equals(l.get("schemeCode").asText())) {
                assertAmount("6.0000", l.get("units")); // 600 / 100
                assertAmount("600", l.get("amount"));
            } else {
                assertAmount("8.0000", l.get("units")); // 400 / 50
            }
        }
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 0, TODAY.toString(), null), 400);
        api.postJson(BASE + "/entries", entry(acc, "SWITCH", null, null, TODAY.toString(), null), 400);
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 10, TODAY.toString(), List.of(line("NOPE", "1"))), 400);
    }

    private void feedNav(String scheme, LocalDate date, String close) {
        UUID instrumentId = UUID.fromString(jdbc.queryForObject(
                "SELECT instrument_id FROM nps_scheme WHERE scheme_code = ?", String.class, scheme));
        InstrumentPrice p = new InstrumentPrice();
        p.setInstrument(instrumentRepository.getReferenceById(instrumentId));
        p.setAsOf(date);
        p.setClose(new BigDecimal(close));
        p.setSource(PriceSource.NPS_TRUST);
        priceRepository.save(p);
    }

    @Test
    void anEntryBeforeTheFirstNavIsEstimatedAtTheNearestLaterNavThenRepricedWhenHistoryLands() throws Exception {
        UUID acc = account(api, "333344446666");
        allocate(acc, 60, 40);
        LocalDate posted = TODAY.minusDays(20);

        // only the feed's last two days exist: the earliest NAV after the date (98.0 / 49.0 on today-2) is used
        JsonNode e = api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 1000, posted.toString(), null), 201);
        assertEquals("ESTIMATED", e.get("status").asText());
        assertEquals(TODAY.minusDays(2).toString(), e.get("navDate").asText());
        for (JsonNode l : e.get("lines")) {
            if (EQ.equals(l.get("schemeCode").asText())) {
                assertAmount("6.1224", l.get("units")); // 600 / 98
            }
        }
        assertTrue(events.stream(PriceRefreshEvent.class).findAny().isPresent(), "a history backfill is requested");

        // the history lands: nearest earlier NAVs 90.0 / 45.0 on posted-1 (and an older one that must not win)
        feedNav(EQ, posted.minusDays(5), "80.0000");
        feedNav(EQ, posted.minusDays(1), "90.0000");
        feedNav(GOV, posted.minusDays(1), "45.0000");
        Set<UUID> instrumentIds = new HashSet<>(jdbc.queryForList(
                "SELECT instrument_id FROM nps_scheme WHERE scheme_code IN (?, ?)", String.class, EQ, GOV)
                .stream().map(UUID::fromString).toList());
        assertEquals(1, repriceService.repriceInstruments(instrumentIds));

        JsonNode after = api.getJson(BASE + "/entries/" + e.get("id").asText(), 200);
        assertEquals("ESTIMATED", after.get("status").asText());
        assertEquals(posted.minusDays(1).toString(), after.get("navDate").asText());
        assertAmount("1000", after.get("amount"));
        for (JsonNode l : after.get("lines")) {
            if (EQ.equals(l.get("schemeCode").asText())) {
                assertAmount("6.6667", l.get("units")); // 600 / 90
                assertAmount("90", l.get("nav"));
                assertAmount("600", l.get("amount"));
            } else {
                assertAmount("8.8889", l.get("units")); // 400 / 45
            }
        }
        // idempotent: nothing left to re-price
        assertEquals(0, repriceService.repriceInstruments(instrumentIds));
    }

    @Test
    void theBackfillEventRepricesAndPersistsTheEntry() throws Exception {
        UUID acc = account(api, "333344448888");
        allocate(acc, 60, 40);
        LocalDate posted = TODAY.minusDays(20);
        JsonNode e = api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 1000, posted.toString(), null), 201);

        feedNav(EQ, posted.minusDays(1), "90.0000");
        feedNav(GOV, posted.minusDays(1), "45.0000");
        Set<UUID> instrumentIds = new HashSet<>(jdbc.queryForList(
                "SELECT instrument_id FROM nps_scheme WHERE scheme_code IN (?, ?)", String.class, EQ, GOV)
                .stream().map(UUID::fromString).toList());
        publisher.publishEvent(new PriceHistoryBackfilledEvent(instrumentIds));

        // read back straight from the database: the change was flushed and committed
        assertEquals(posted.minusDays(1).toString(), jdbc.queryForObject(
                "SELECT CAST(nav_date AS VARCHAR(10)) FROM nps_entry WHERE id = ?", String.class, e.get("id").asText()));
        assertEquals(0, new BigDecimal("6.6667").compareTo(jdbc.queryForObject(
                "SELECT units FROM nps_entry_line WHERE entry_id = ? AND scheme_code = ?", BigDecimal.class, e.get("id").asText(), EQ)));
    }

    @Test
    void aSchemeWithNoNavAtAllCannotBeEstimated() throws Exception {
        UUID acc = account(api, "333344447777");
        allocate(acc, 60, 40);
        jdbc.update("DELETE FROM instrument_prices WHERE instrument_id IN "
                + "(SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT99%')");
        JsonNode err = api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 1000, "2010-01-01", null), 400);
        assertTrue(err.toString().contains("No NAV is available yet for scheme"), err.toString());
    }

    @Test
    void entriesPageNewestFirstWithFilters() throws Exception {
        UUID acc = account(api, "444455556666");
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2025-04-01", List.of(line(EQ, "10"))), 201);
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 500, "2026-04-10", List.of(line(EQ, "5"))), 201);
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "EMPLOYER", 300, "2026-06-10", List.of(line(GOV, "6"))), 201);

        JsonNode page = api.getJson(BASE + "/entries?size=2&page=0", 200);
        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(2, page.get("content").size());
        assertEquals("2026-06-10", page.get("content").get(0).get("postedDate").asText());
        assertEquals("2026-04-10", page.get("content").get(1).get("postedDate").asText());
        assertEquals("2025-04-01", api.getJson(BASE + "/entries?size=2&page=1", 200).get("content").get(0).get("postedDate").asText());
        assertEquals(2, api.getJson(BASE + "/entries?type=CONTRIBUTION", 200).get("totalElements").asInt());
        assertEquals(2, api.getJson(BASE + "/entries?schemeCode=" + EQ, 200).get("totalElements").asInt());
        assertEquals(0, api.getJson(BASE + "/entries?tier=II", 200).get("totalElements").asInt());
        assertEquals(3, api.getJson(BASE + "/entries?accountId=" + acc, 200).get("totalElements").asInt());
        assertEquals(0, api.getJson(BASE + "/entries?accountId=" + UUID.randomUUID(), 200).get("totalElements").asInt());
    }

    @Test
    void entriesCanBeEditedAndDeletedAndAWithdrawalIsStoredNegative() throws Exception {
        UUID acc = account(api, "555566667777");
        JsonNode created = api.postJson(BASE + "/entries", entry(acc, "WITHDRAWAL", null, 200, "2026-05-01",
                List.of(line(EQ, "-2"))), 201);
        assertAmount("-200", created.get("amount"));
        String id = created.get("id").asText();

        JsonNode edited = api.putJson(BASE + "/entries/" + id, entry(acc, "WITHDRAWAL", null, -300, "2026-05-02",
                List.of(line(EQ, "-3"))), 200);
        assertAmount("-300", edited.get("amount"));
        assertEquals(1, edited.get("lines").size());
        assertAmount("-3", edited.get("lines").get(0).get("units"));

        api.deleteJson(BASE + "/entries/" + id, 204);
        api.getJson(BASE + "/entries/" + id, 404);
    }

    // ------------------------------------------------------------------ summary

    @Test
    void summaryTotalsValueInvestedGainFyAndFlags() throws Exception {
        UUID acc = account(api, "666677778888");
        allocate(acc, 60, 40);
        // opening balance: 10 units of EQ and 20 of GOV for 2000 invested (before this FY)
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 2000, "2025-10-01",
                List.of(line(EQ, "10"), line(GOV, "20"))), 201);
        // this FY: own 1000 (confirmed lines) and employer 500 (estimated from allocation)
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 1000, "2026-05-01",
                List.of(line(EQ, "4"), line(GOV, "12"))), 201);
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "EMPLOYER", 500, TODAY.toString(), null), 201);
        // a charge takes units but does not change what was invested
        api.postJson(BASE + "/entries", entry(acc, "CHARGE", null, null, "2026-09-01", List.of(line(EQ, "-0.5"))), 201);
        // a pending entry counts for nothing
        Map<String, Object> pending = entry(acc, "CONTRIBUTION", "SELF", 777, "2026-10-01", List.of(line(EQ, "7")));
        UUID pendingId = UUID.fromString(api.postJson(BASE + "/entries", pending, 201).get("id").asText());
        jdbc.update("UPDATE nps_entry SET status = 'PENDING' WHERE id = ?", pendingId.toString());

        JsonNode s = api.getJson(BASE + "/summary", 200);

        // units: EQ 10 + 4 + 3 (300/100) - 0.5 = 16.5 -> 1650; GOV 20 + 12 + 4 (200/50) = 36 -> 1800
        assertAmount("3450", s.get("hero").get("value"));
        assertAmount("3500", s.get("hero").get("invested"));
        assertAmount("-50", s.get("hero").get("gain"));
        assertAmount("-1.43", s.get("hero").get("gainPct"));
        assertAmount("1500", s.get("hero").get("contributedThisFy"));
        assertEquals(TODAY.minusDays(1).toString(), s.get("hero").get("navAsOf").asText());
        // the opening entry is 13 months old, so the XIRR gate is open
        assertFalse(s.get("hero").get("xirr").isNull());
        assertAmount("1000", s.get("thisFy").get("self"));
        assertAmount("500", s.get("thisFy").get("employer"));
        assertEquals("I", s.get("thisFy").get("byTier").get(0).get("tier").asText());
        assertAmount("1000", s.get("thisFy").get("byTier").get(0).get("self"));
        assertAmount("500", s.get("thisFy").get("byTier").get(0).get("employer"));
        assertAmount("0", s.get("thisFy").get("byTier").get(1).get("self"));
        assertTrue(s.get("flags").get("hasEstimated").asBoolean());
        assertFalse(s.get("flags").get("navStale").asBoolean());
        assertFalse(s.get("flags").get("unitsMismatch").asBoolean());

        JsonNode account = s.get("accounts").get(0);
        assertEquals("XXXX XXXX 8888", account.get("pranMasked").asText());
        JsonNode tier1 = account.get("tiers").get(0);
        assertEquals("I", tier1.get("tier").asText());
        assertAmount("3450", tier1.get("value"));
        assertAmount("3500", tier1.get("invested"));
        assertEquals(2, tier1.get("schemes").size());

        JsonNode eq = null;
        for (JsonNode h : s.get("holdings")) {
            if (EQ.equals(h.get("schemeCode").asText())) {
                eq = h;
            }
        }
        assertAmount("16.5", eq.get("units"));
        assertAmount("100", eq.get("nav"));
        assertAmount("1650", eq.get("value"));
        assertTrue(eq.get("estimated").asBoolean());
        assertEquals("E", eq.get("assetClass").asText());
    }

    @Test
    void navOlderThanFourDaysIsStaleAndXirrAppearsAfterAYear() throws Exception {
        UUID acc = account(api, "777788889999");
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2025-08-01", List.of(line(EQ, "10"))), 201);
        jdbc.update("UPDATE instrument_prices SET as_of = DATEADD('DAY', -10, as_of) WHERE instrument_id IN "
                + "(SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT99%')");

        JsonNode s = api.getJson(BASE + "/summary", 200);

        assertTrue(s.get("flags").get("navStale").asBoolean());
        assertFalse(s.get("flags").get("hasEstimated").asBoolean());
        // opened 15 months before the business date: gate open; 1000 -> 1000 is about 0% a year
        assertFalse(s.get("hero").get("xirr").isNull());
        assertEquals(0.0, s.get("hero").get("xirr").asDouble(), 0.5);
    }

    @Test
    void netWorthCountsOnlyTheUsersOwnAccounts() throws Exception {
        UUID own = account(api, "888899990000");
        api.postJson(BASE + "/entries", entry(own, "OPENING", null, 1000, "2025-08-01", List.of(line(EQ, "10"))), 201);
        UUID child = api.create(BASE + "/accounts", Map.of("pran", "000011112222", "holderName", "Kid",
                "relation", "CHILD", "sector", "VATSALYA", "cra", "PROTEAN"));
        api.postJson(BASE + "/entries", entry(child, "OPENING", null, 400, "2025-08-01", List.of(line(GOV, "8"))), 201);

        // own 10 x 100 = 1000; the child's 8 x 50 = 400 stays out of net worth but is in the summary
        assertAmount("1400", api.getJson(BASE + "/summary", 200).get("hero").get("value"));
        asUser(api, () -> assertEquals(0, npsService.getSelfValueOrNull().compareTo(new BigDecimal("1000"))));
        asUser(otherApi, () -> assertNull(npsService.getSelfValueOrNull()));
    }

    // ------------------------------------------------------------------ polish (S1b)

    @Test
    void entriesFilterByPostedDateRangeAndContributor() throws Exception {
        UUID acc = account(api, "121200001111");
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2025-04-01", List.of(line(EQ, "10"))), 201);
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 500, "2026-04-10", List.of(line(EQ, "5"))), 201);
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "EMPLOYER", 300, "2026-06-10", List.of(line(GOV, "6"))), 201);

        assertEquals(2, api.getJson(BASE + "/entries?from=2026-04-01&to=2026-06-10", 200).get("totalElements").asInt()); // inclusive
        assertEquals(1, api.getJson(BASE + "/entries?from=2026-04-11", 200).get("totalElements").asInt());
        assertEquals(2, api.getJson(BASE + "/entries?to=2026-04-10", 200).get("totalElements").asInt());
        assertEquals(1, api.getJson(BASE + "/entries?contributor=EMPLOYER", 200).get("totalElements").asInt());
        JsonNode self = api.getJson(BASE + "/entries?contributor=SELF&from=2026-04-01&to=2026-03-31", 200);
        assertEquals(0, self.get("totalElements").asInt());
        assertEquals(1, api.getJson(BASE + "/entries?contributor=SELF&from=2026-04-01&to=2027-03-31", 200).get("totalElements").asInt());
    }

    @Test
    void heroHasDayChangeAgainstThePreviousNavAndTheInvestedSplit() throws Exception {
        UUID acc = account(api, "121200002222");
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 2000, "2025-10-01", List.of(line(EQ, "10"), line(GOV, "20"))), 201);
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "EMPLOYER", 500, "2026-05-01", List.of(line(EQ, "5"))), 201);
        api.postJson(BASE + "/entries", entry(acc, "CONTRIBUTION", "SELF", 100, "2026-05-02", List.of(line(EQ, "1"))), 201);

        JsonNode hero = api.getJson(BASE + "/summary", 200).get("hero");

        // value 16 x 100 + 20 x 50 = 2600; previous NAVs 98 and 49 -> 16 x 98 + 20 x 49 = 2548
        assertAmount("2600", hero.get("value"));
        assertAmount("52", hero.get("dayChange"));
        assertAmount("2.04", hero.get("dayChangePct")); // 52 / 2548
        assertAmount("2100", hero.get("investedSelf")); // the opening balance counts as SELF
        assertAmount("500", hero.get("investedEmployer"));
        assertAmount("2600", hero.get("invested"));
    }

    @Test
    void dayChangeIsNullWithoutAPreviousNavOrHoldings() throws Exception {
        UUID acc = account(api, "121200003333");
        assertTrue(api.getJson(BASE + "/summary", 200).get("hero").get("dayChange").isNull());

        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2025-10-01", List.of(line(EQ, "10"))), 201);
        jdbc.update("DELETE FROM instrument_prices WHERE as_of < ? AND instrument_id IN "
                + "(SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT99%')", java.sql.Date.valueOf(TODAY.minusDays(1)));
        JsonNode hero = api.getJson(BASE + "/summary", 200).get("hero");
        assertTrue(hero.get("dayChange").isNull());
        assertTrue(hero.get("dayChangePct").isNull());
    }

    private void statement(UUID acc, LocalDate end, boolean incomplete) {
        jdbc.update("INSERT INTO nps_statement (id, user_id, account_id, tier, period_start, period_end, source, "
                + "units_mismatch, incomplete, imported_at) VALUES (?, ?, ?, 'I', ?, ?, 'UPLOAD', 0, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), userId.toString(), acc.toString(),
                java.sql.Date.valueOf(end.minusMonths(1)), java.sql.Date.valueOf(end), incomplete ? 1 : 0);
    }

    @Test
    void accountsCarryTheLatestCompleteStatementEndAndTheSummaryFlagsAStaleOne() throws Exception {
        UUID acc = account(api, "121200004444");
        UUID bare = account(api, "121200005555");
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2025-10-01", List.of(line(EQ, "10"))), 201);

        JsonNode none = api.getJson(BASE + "/summary", 200);
        assertTrue(none.get("accounts").get(0).get("latestStatementEnd").isNull());
        assertFalse(none.get("flags").get("statementStale").asBoolean()); // no statements: nothing to be stale

        statement(acc, TODAY.minusDays(100), false);
        statement(acc, TODAY.minusDays(30), true); // incomplete: ignored
        JsonNode old = api.getJson(BASE + "/summary", 200);
        assertTrue(old.get("flags").get("statementStale").asBoolean());
        for (JsonNode a : old.get("accounts")) {
            assertEquals(a.get("id").asText().equals(acc.toString()) ? TODAY.minusDays(100).toString() : "null",
                    a.get("latestStatementEnd").isNull() ? "null" : a.get("latestStatementEnd").asText());
        }

        statement(acc, TODAY.minusDays(45), false); // exactly 45 days old is not "more than 45"
        JsonNode edge = api.getJson(BASE + "/summary", 200);
        assertFalse(edge.get("flags").get("statementStale").asBoolean());
        assertEquals(TODAY.minusDays(45).toString(), accountById(edge, acc).get("latestStatementEnd").asText());

        statement(acc, TODAY.minusDays(46), false);
        statement(bare, TODAY.minusDays(200), false); // an account without entries does not count
        jdbc.update("DELETE FROM nps_statement WHERE period_end = ?", java.sql.Date.valueOf(TODAY.minusDays(45)));
        assertTrue(api.getJson(BASE + "/summary", 200).get("flags").get("statementStale").asBoolean());
    }

    private static JsonNode accountById(JsonNode summary, UUID id) {
        for (JsonNode a : summary.get("accounts")) {
            if (a.get("id").asText().equals(id.toString())) {
                return a;
            }
        }
        throw new AssertionError("account " + id + " not in the summary");
    }

    @Test
    void anOpeningBalanceWithoutLinesIsEstimatedLikeAContribution() throws Exception {
        UUID acc = account(api, "121200006666");
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2026-11-04", null), 400); // no allocation yet
        allocate(acc, 60, 40);

        JsonNode e = api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2026-11-04", null), 201);

        assertEquals("OPENING", e.get("type").asText());
        assertEquals("ESTIMATED", e.get("status").asText());
        assertAmount("1000", e.get("amount"));
        assertEquals(2, e.get("lines").size());
        for (JsonNode l : e.get("lines")) {
            if (EQ.equals(l.get("schemeCode").asText())) {
                assertAmount("6.0000", l.get("units")); // 600 at the 04 Nov NAV of 100
                assertAmount("600", l.get("amount"));
            } else {
                assertAmount("8.0000", l.get("units")); // 400 at 50
            }
        }
        assertTrue(api.getJson(BASE + "/summary", 200).get("flags").get("hasEstimated").asBoolean());
        // with lines given the entry stays confirmed, as before
        JsonNode given = api.postJson(BASE + "/entries", entry(acc, "OPENING", null, 1000, "2026-11-04", List.of(line(EQ, "10"))), 201);
        assertEquals("CONFIRMED", given.get("status").asText());
        api.postJson(BASE + "/entries", entry(acc, "OPENING", null, null, "2026-11-04", null), 400);
    }

    private void asUser(ApiTestClient client, Runnable check) {
        UUID id = client == api ? userId : otherUserId;
        UUID previous = com.financeos.core.security.UserContext.getCurrentUserId();
        com.financeos.core.security.UserContext.setCurrentUserId(id);
        try {
            check.run();
        } finally {
            if (previous == null) {
                com.financeos.core.security.UserContext.clear();
            } else {
                com.financeos.core.security.UserContext.setCurrentUserId(previous);
            }
        }
    }
}
