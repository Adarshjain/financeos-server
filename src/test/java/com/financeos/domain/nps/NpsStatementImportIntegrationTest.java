package com.financeos.domain.nps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.nps.dto.NpsImportResultResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.price.NpsTrustFeedClient;
import com.financeos.domain.nps.statement.NpsStatementParser;
import com.financeos.domain.nps.statement.NpsTxnKind;
import com.financeos.domain.nps.statement.ParsedNpsStatement;
import com.financeos.domain.user.User;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NPS statement import over real persistence, with the dummy-subscriber fixtures of src/test/resources/nps. */
@SpringBootTest
@AutoConfigureMockMvc
class NpsStatementImportIntegrationTest {

    private static final String BASE = "/api/v1/nps";
    private static final String IMPORT = BASE + "/import";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> PW = List.of("ravi1406");
    private static final String PRAN = "110012345678";
    private static final String POP_E = "SM008001";
    private static final String POP_C = "SM008002";
    private static final String POP_G = "SM008003";
    private static final String DIR_E = "SM008018";
    private static final String DIR_C = "SM008019";
    private static final String DIR_G = "SM008020";
    private static final List<String> CODES = List.of(POP_E, POP_C, POP_G, DIR_E, DIR_C, DIR_G);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired NpsCatalogService catalogService;
    @Autowired NpsStatementImportService importService;
    @Autowired NpsStatementParser parser;
    @Autowired NpsSchemeResolver resolver;
    @Autowired InstrumentRepository instrumentRepository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private String email;
    private int pranSeq;

    @BeforeEach
    void setUp() throws Exception {
        email = "nps-import-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String other = "nps-import-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, other);
        otherUserId = userRepository.findByEmail(other).orElseThrow().getId();
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 11, 5).atTime(10, 0).atZone(IST).toInstant(), IST));
        seedCatalog();
    }

    @AfterEach
    void cleanUp() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    @AfterAll
    static void dropCatalog(@Autowired JdbcTemplate jdbc) {
        String in = "('" + String.join("','", CODES) + "')";
        jdbc.update("DELETE FROM instrument_prices WHERE instrument_id IN (SELECT instrument_id FROM nps_scheme WHERE scheme_code IN " + in + ")");
        List<String> instrumentIds = jdbc.queryForList("SELECT instrument_id FROM nps_scheme WHERE scheme_code IN " + in, String.class);
        jdbc.update("DELETE FROM nps_scheme WHERE scheme_code IN " + in);
        instrumentIds.forEach(id -> jdbc.update("DELETE FROM instruments WHERE id = ?", id));
    }

    /** The old POP schemes of the wide statements and the DIRECT ones of the stacked ones, names as the NPS Trust feed has them. */
    private void seedCatalog() {
        LocalDate navDay = LocalDate.of(2025, 1, 14);
        List<NpsTrustFeedClient.NavRow> rows = new ArrayList<>();
        String popPfm = "HDFC Pension Management Company Limited";
        String dirPfm = "HDFC Pension Fund Management Limited";
        rows.add(new NpsTrustFeedClient.NavRow(POP_E, "HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME E - TIER I", popPfm, navDay, new BigDecimal("62.4417")));
        rows.add(new NpsTrustFeedClient.NavRow(POP_C, "HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME C - TIER I", popPfm, navDay, new BigDecimal("41.8820")));
        rows.add(new NpsTrustFeedClient.NavRow(POP_G, "HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME G - TIER I", popPfm, navDay, new BigDecimal("37.5541")));
        LocalDate d2 = LocalDate.of(2026, 6, 30);
        rows.add(new NpsTrustFeedClient.NavRow(DIR_E, "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT", dirPfm, d2, new BigDecimal("63.1086")));
        rows.add(new NpsTrustFeedClient.NavRow(DIR_C, "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME C - TIER I DIRECT", dirPfm, d2, new BigDecimal("41.9907")));
        rows.add(new NpsTrustFeedClient.NavRow(DIR_G, "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME G - TIER I DIRECT", dirPfm, d2, new BigDecimal("37.6123")));
        catalogService.apply(rows.subList(0, 3));
        catalogService.apply(rows.subList(3, 6));
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] pdf(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/nps/nps-protean-" + name + ".pdf"));
    }

    private ParsedNpsStatement parsed(String name) throws Exception {
        return parser.parse(pdf(name), PW);
    }

    private UUID account(ApiTestClient client, String pran) throws Exception {
        return client.create(BASE + "/accounts", Map.of("pran", pran, "holderName", "Ravi", "relation", "SELF",
                "sector", "ALL_CITIZEN", "cra", "PROTEAN"));
    }

    private UUID freshAccount() throws Exception {
        return account(api, String.valueOf(900000000000L + (++pranSeq)));
    }

    private NpsImportResultResponse apply(UUID account, ParsedNpsStatement p) {
        return importService.apply(userId, account, p, NpsStatementSource.UPLOAD, null, false);
    }

    private NpsImportResultResponse apply(UUID account, String fixture) throws Exception {
        return apply(account, parsed(fixture));
    }

    private Map<String, BigDecimal> units(UUID account) {
        Map<String, BigDecimal> out = new TreeMap<>();
        jdbc.query("SELECT l.scheme_code, SUM(l.units) AS u FROM nps_entry_line l JOIN nps_entry e ON e.id = l.entry_id "
                + "WHERE e.account_id = ? AND e.status <> 'PENDING' GROUP BY l.scheme_code", rs -> {
            BigDecimal u = rs.getBigDecimal("u").setScale(4);
            if (u.signum() != 0) {
                out.put(rs.getString("scheme_code"), u);
            }
        }, account.toString());
        return out;
    }

    private BigDecimal invested(UUID account) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM nps_entry WHERE account_id = ? "
                + "AND type IN ('CONTRIBUTION', 'OPENING', 'WITHDRAWAL')", BigDecimal.class, account.toString());
        return v.setScale(2);
    }

    /** Every entry of the account as "date type amount lines", in a stable order, with no ids. */
    private List<String> entrySet(UUID account) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : jdbc.queryForList("SELECT id, posted_date, type, amount, description FROM nps_entry "
                + "WHERE account_id = ?", account.toString())) {
            Map<String, BigDecimal> lines = new TreeMap<>();
            jdbc.query("SELECT scheme_code, units FROM nps_entry_line WHERE entry_id = ?",
                    rs -> { lines.put(rs.getString(1), rs.getBigDecimal(2).setScale(4)); }, e.get("id").toString());
            BigDecimal amount = (BigDecimal) e.get("amount");
            out.add(e.get("posted_date") + " " + e.get("type") + " " + (amount == null ? "null" : amount.setScale(2))
                    + " " + e.get("description") + " " + lines);
        }
        out.sort(String::compareTo);
        return out;
    }

    private List<Map<String, Object>> entries(UUID account, String type) {
        return jdbc.queryForList("SELECT * FROM nps_entry WHERE account_id = ? AND type = ? ORDER BY posted_date",
                account.toString(), type);
    }

    private Map<String, BigDecimal> lines(Object entryId) {
        Map<String, BigDecimal> out = new TreeMap<>();
        jdbc.query("SELECT scheme_code, units FROM nps_entry_line WHERE entry_id = ?",
                rs -> { out.put(rs.getString(1), rs.getBigDecimal(2).setScale(4)); }, entryId.toString());
        return out;
    }

    private static LocalDate date(Object sqlDate) {
        return sqlDate instanceof java.sql.Timestamp t ? t.toLocalDateTime().toLocalDate() : ((java.sql.Date) sqlDate).toLocalDate();
    }

    private static void assertDec(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), "expected " + expected + " but was " + actual);
    }

    private int count(String table, UUID account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE account_id = ?", Integer.class, account.toString());
    }

    // ------------------------------------------------------------------ first import

    @Test
    void firstImportCreatesOpeningChargeAndContributionAndReimportIsANoOp() throws Exception {
        UUID acc = freshAccount();
        NpsImportResultResponse r = apply(acc, "wide-contribution");

        assertEquals(2, r.added());
        assertEquals(0, r.upgraded());
        assertEquals(0, r.skipped());
        assertFalse(r.unitsMismatch());
        assertFalse(r.incomplete());
        assertTrue(r.unmapped().isEmpty());
        assertEquals(3, r.adjustments().size());

        List<Map<String, Object>> opening = entries(acc, "OPENING");
        assertEquals(1, opening.size());
        assertDec("275400.00", opening.get(0).get("amount"));
        assertEquals(LocalDate.of(2025, 1, 1), date(opening.get(0).get("posted_date")));
        assertEquals(Map.of(POP_E, new BigDecimal("4210.5521"), POP_C, new BigDecimal("1890.3307"),
                POP_G, new BigDecimal("2405.7712")), lines(opening.get(0).get("id")));

        List<Map<String, Object>> charge = entries(acc, "CHARGE");
        assertEquals(1, charge.size());
        assertEquals(3, lines(charge.get(0).get("id")).size());
        assertDec("-61.90", charge.get(0).get("amount"));
        assertEquals("STATEMENT", charge.get(0).get("source"));
        assertEquals("Billing for Q3, 2024-2025", charge.get(0).get("description"));

        List<Map<String, Object>> contribution = entries(acc, "CONTRIBUTION");
        assertEquals(1, contribution.size());
        assertDec("37350.00", contribution.get(0).get("amount"));
        assertEquals("SELF", contribution.get(0).get("contributor"));
        assertEquals("CONFIRMED", contribution.get(0).get("status"));
        assertEquals(Map.of(POP_E, new BigDecimal("299.0790"), POP_C, new BigDecimal("267.5374"),
                POP_G, new BigDecimal("198.9130")), lines(contribution.get(0).get("id")));
        // the feed has the line NAVs for 14 Jan, the day before the statement date
        assertEquals(LocalDate.of(2025, 1, 14), date(contribution.get(0).get("nav_date")));

        // the ledger closes where the statement closes, and what was invested is what the statement says
        assertEquals(Map.of(POP_E, new BigDecimal("4508.9644"), POP_C, new BigDecimal("2157.5713"),
                POP_G, new BigDecimal("2604.4642")), units(acc));
        assertDec("312750.00", invested(acc));
        assertFalse(jdbc.queryForObject("SELECT units_mismatch FROM nps_statement WHERE account_id = ?", Boolean.class, acc.toString()));

        // the account took the statement's choice and allocation
        Map<String, Object> account = jdbc.queryForMap("SELECT scheme_choice FROM nps_account WHERE id = ?", acc.toString());
        assertEquals("AGGRESSIVE CHOICE", account.get("scheme_choice"));
        assertEquals(3, count("nps_allocation", acc));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_allocation WHERE account_id = ? AND source = 'STATEMENT' "
                + "AND scheme_code = ? AND percent = 50", Integer.class, acc.toString(), POP_E));

        // again: nothing new, everything skipped
        int before = count("nps_entry", acc);
        NpsImportResultResponse again = apply(acc, "wide-contribution");
        assertEquals(0, again.added());
        assertEquals(2, again.skipped());
        assertEquals(r.statementId(), again.statementId());
        assertTrue(again.warnings().contains("Already imported"));
        assertEquals(before, count("nps_entry", acc));
        assertEquals(1, count("nps_statement", acc));
    }

    @Test
    void anEmailEstimateIsUpgradedInPlaceKeepingItsTransactionAndNote() throws Exception {
        UUID acc = freshAccount();
        UUID txn = api.create("/api/v1/transactions", Map.of("accountId", api.create("/api/v1/accounts",
                        Map.of("type", "bank_account", "name", "Bank 1234", "last4", "1234", "openingBalance", 1000,
                                "financialPosition", "asset", "excludeFromNetAsset", false)).toString(),
                "date", "2025-01-17", "amount", -37350, "description", "NPS contribution"));
        NpsEntry estimate = new NpsEntry();
        estimate.setUser(userRepository.getReferenceById(userId));
        estimate.setAccount(accountRepository().getReferenceById(acc));
        estimate.setTier(NpsTier.I);
        estimate.setType(NpsEntryType.CONTRIBUTION);
        estimate.setContributor(NpsContributor.SELF);
        estimate.setAmount(new BigDecimal("37350.00"));
        estimate.setPostedDate(LocalDate.of(2025, 1, 17));
        estimate.setSource(NpsEntrySource.EMAIL);
        estimate.setStatus(NpsEntryStatus.ESTIMATED);
        estimate.setNote("keep me");
        estimate.addLine(new NpsEntryLine(POP_E, new BigDecimal("100.0000"), new BigDecimal("62.0000"), new BigDecimal("6200.00")));
        UUID estimateId = entryRepository().saveAndFlush(estimate).getId();
        jdbc.update("UPDATE nps_entry SET transaction_id = ? WHERE id = ?", txn.toString(), estimateId.toString());

        NpsImportResultResponse r = apply(acc, "wide-contribution");

        assertEquals(1, r.upgraded());
        assertEquals(1, r.added()); // the charge
        List<Map<String, Object>> contributions = entries(acc, "CONTRIBUTION");
        assertEquals(1, contributions.size());
        Map<String, Object> row = contributions.get(0);
        assertEquals(estimateId.toString(), row.get("id").toString());
        assertEquals("STATEMENT", row.get("source"));
        assertEquals("CONFIRMED", row.get("status"));
        assertEquals(txn.toString(), row.get("transaction_id").toString());
        assertEquals("keep me", row.get("note"));
        assertEquals(LocalDate.of(2025, 1, 15), date(row.get("posted_date")));
        assertDec("37350.00", row.get("amount"));
        assertEquals(Map.of(POP_E, new BigDecimal("299.0790"), POP_C, new BigDecimal("267.5374"),
                POP_G, new BigDecimal("198.9130")), lines(estimateId));
        assertDec("312750.00", invested(acc));
        assertFalse(r.unitsMismatch());
    }

    // ------------------------------------------------------------------ order independence

    @Test
    void anyImportOrderGivesTheSameLedger() throws Exception {
        UUID forward = freshAccount();
        apply(forward, "wide-contribution");
        List<String> afterJan = entrySet(forward);
        NpsImportResultResponse empty = apply(forward, "wide-empty");
        // an empty month adds no entry and needs no adjustment
        assertEquals(0, empty.added());
        assertTrue(empty.adjustments().isEmpty());
        assertFalse(empty.unitsMismatch());
        assertEquals(afterJan, entrySet(forward));
        apply(forward, "stacked-rebalance");

        UUID reverse = freshAccount();
        apply(reverse, "stacked-rebalance");
        apply(reverse, "wide-empty");
        apply(reverse, "wide-contribution");

        assertEquals(units(forward), units(reverse));
        assertEquals(Map.of(DIR_E, new BigDecimal("4080.4243"), DIR_C, new BigDecimal("2006.6134"),
                DIR_G, new BigDecimal("2492.4528")), units(forward));
        assertEquals(invested(forward), invested(reverse));
        assertDec("312750.00", invested(forward));
        assertEquals(entrySet(forward), entrySet(reverse));
        for (UUID acc : List.of(forward, reverse)) {
            assertEquals(3, count("nps_statement", acc));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE account_id = ? AND units_mismatch = TRUE",
                    Integer.class, acc.toString()));
        }
        // a third arrangement: middle one last
        UUID shuffled = freshAccount();
        apply(shuffled, "wide-contribution");
        apply(shuffled, "stacked-rebalance");
        apply(shuffled, "wide-empty");
        assertEquals(entrySet(forward), entrySet(shuffled));
    }

    @Test
    void aStatementOfDifferentSchemesAdjustsTheLedgerAndMovesNoMoney() throws Exception {
        UUID acc = freshAccount();
        apply(acc, "wide-contribution");
        BigDecimal investedBefore = invested(acc);

        NpsImportResultResponse r = apply(acc, "stacked-rebalance");

        Map<String, BigDecimal> adj = new TreeMap<>();
        r.adjustments().forEach(a -> adj.put(a.schemeCode(), a.units().setScale(4)));
        assertEquals(Map.of(POP_E, new BigDecimal("-4508.9644"), POP_C, new BigDecimal("-2157.5713"),
                POP_G, new BigDecimal("-2604.4642"), DIR_E, new BigDecimal("4210.5521"),
                DIR_C, new BigDecimal("1890.3307"), DIR_G, new BigDecimal("2405.7712")), adj);
        assertEquals(1, r.added());
        assertFalse(r.unitsMismatch());

        List<Map<String, Object>> adjustment = entries(acc, "ADJUSTMENT");
        assertEquals(1, adjustment.size());
        assertNull(adjustment.get(0).get("amount"));
        assertEquals(LocalDate.of(2026, 6, 1), date(adjustment.get(0).get("posted_date")));
        List<Map<String, Object>> rebalance = entries(acc, "REBALANCE");
        assertEquals(1, rebalance.size());
        assertDec("0.00", rebalance.get(0).get("amount"));
        assertEquals(3, lines(rebalance.get(0).get("id")).size());

        assertEquals(investedBefore, invested(acc));
        assertEquals(Map.of(DIR_E, new BigDecimal("4080.4243"), DIR_C, new BigDecimal("2006.6134"),
                DIR_G, new BigDecimal("2492.4528")), units(acc));
        // the account now follows the latest statement's choice and allocation
        assertEquals("Life Cycle 75 - High (15E / 55 Y)",
                jdbc.queryForObject("SELECT scheme_choice FROM nps_account WHERE id = ?", String.class, acc.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_allocation WHERE account_id = ? AND scheme_code = ? "
                + "AND percent = 73.5", Integer.class, acc.toString(), DIR_E));
        assertEquals(3, count("nps_allocation", acc));
    }

    // ------------------------------------------------------------------ incomplete, unmapped, mismatch

    @Test
    void anIncompleteStatementIsRecordedWithoutEntriesAndSkippedByTheChecks() throws Exception {
        ParsedNpsStatement empty = parsed("wide-empty");
        ParsedNpsStatement noFigures = new ParsedNpsStatement(empty.pran(), empty.subscriberName(), empty.periodStart(),
                empty.periodEnd(), empty.layout(), empty.tierStatuses(), empty.schemeChoice(), empty.prefs(),
                empty.summary(), List.of(), empty.contributions(), empty.txns(), List.of("Scheme-wise holdings missing"));
        UUID acc = freshAccount();
        apply(acc, "wide-contribution");
        List<String> before = entrySet(acc);

        NpsImportResultResponse r = apply(acc, noFigures);

        assertTrue(r.incomplete());
        assertEquals(0, r.added());
        assertTrue(r.warnings().contains("Scheme-wise holdings missing"));
        assertTrue(r.warnings().contains("Protean sent this month without figures"));
        assertEquals(before, entrySet(acc));
        assertTrue(jdbc.queryForObject("SELECT incomplete FROM nps_statement WHERE id = ?", Boolean.class, r.statementId().toString()));
        assertFalse(jdbc.queryForObject("SELECT units_mismatch FROM nps_statement WHERE id = ?", Boolean.class, r.statementId().toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement_holding WHERE statement_id = ?", Integer.class, r.statementId().toString()));
        // Gmail would not retry it: the same period is already there
        assertTrue(apply(acc, noFigures).warnings().contains("Already imported"));

        // the next complete statement is reconciled as if the incomplete one was not there
        NpsImportResultResponse june = apply(acc, "stacked-rebalance");
        assertFalse(june.unitsMismatch());
        assertEquals(6, june.adjustments().size());
        List<Listed> listed = statements(acc);
        assertEquals(3, listed.size());
        assertEquals(1, listed.stream().filter(Listed::incomplete).count());
    }

    private record Listed(boolean incomplete) {
    }

    private List<Listed> statements(UUID acc) throws Exception {
        JsonNode list = api.getJson(IMPORT + "/statements?accountId=" + acc, 200);
        List<Listed> out = new ArrayList<>();
        list.forEach(n -> out.add(new Listed(n.get("incomplete").asBoolean())));
        return out;
    }

    @Test
    void aTransactionWithoutUnitsMakesTheStatementIncomplete() throws Exception {
        ParsedNpsStatement p = parsed("wide-contribution");
        assertFalse(NpsStatementImportService.isIncomplete(p.holdings(), p.txns()));
        assertTrue(NpsStatementImportService.isIncomplete(List.of(), p.txns()));
        List<ParsedNpsStatement.Holding> noUnits = new ArrayList<>(p.holdings());
        noUnits.set(0, new ParsedNpsStatement.Holding(noUnits.get(0).schemeName(), null, null, null, null));
        assertTrue(NpsStatementImportService.isIncomplete(noUnits, p.txns()));
        List<ParsedNpsStatement.Txn> txns = new ArrayList<>(p.txns());
        int i = 0;
        while (txns.get(i).kind() != NpsTxnKind.CONTRIBUTION) {
            i++;
        }
        ParsedNpsStatement.Txn t = txns.get(i);
        txns.set(i, new ParsedNpsStatement.Txn(t.schemeName(), t.date(), t.description(), t.kind(), t.amount(), t.nav(), null, null));
        assertTrue(NpsStatementImportService.isIncomplete(p.holdings(), txns));
        // an opening or closing row without units is not a transaction
        List<ParsedNpsStatement.Txn> bookends = new ArrayList<>();
        for (ParsedNpsStatement.Txn x : p.txns()) {
            bookends.add(x.kind() == NpsTxnKind.OPENING
                    ? new ParsedNpsStatement.Txn(x.schemeName(), x.date(), x.description(), x.kind(), null, null, null, null) : x);
        }
        assertFalse(NpsStatementImportService.isIncomplete(p.holdings(), bookends));
    }

    @Test
    void anUnrecognisedSchemeIsReportedAndItsLinesAreSkipped() throws Exception {
        ParsedNpsStatement p = parsed("wide-contribution");
        String gName = p.holdings().get(2).schemeName();
        String unknown = "ZZZ PENSION LIMITED SCHEME G - TIER I";
        ParsedNpsStatement renamed = new ParsedNpsStatement(p.pran(), p.subscriberName(), p.periodStart(), p.periodEnd(),
                p.layout(), p.tierStatuses(), p.schemeChoice(),
                p.prefs().stream().map(x -> new ParsedNpsStatement.Pref(x.schemeName().equals(gName) ? unknown : x.schemeName(), x.percent())).toList(),
                p.summary(),
                p.holdings().stream().map(h -> new ParsedNpsStatement.Holding(h.schemeName().equals(gName) ? unknown : h.schemeName(), h.units(), h.nav(), h.navDate(), h.value())).toList(),
                p.contributions(),
                p.txns().stream().map(t -> new ParsedNpsStatement.Txn(t.schemeName().equals(gName) ? unknown : t.schemeName(), t.date(), t.description(), t.kind(), t.amount(), t.nav(), t.units(), t.chargeTotal())).toList(),
                p.warnings());
        UUID acc = freshAccount();

        // a preview of it leaves nothing behind and marks the rows
        NpsStatementImportService.Outcome dry = importService.applyWithRows(userId, acc, renamed,
                NpsStatementSource.UPLOAD, null, null, true);
        assertEquals(2, dry.result().added());
        assertEquals(List.of(unknown), dry.result().unmapped());
        assertTrue(dry.result().warnings().stream().anyMatch(w -> w.contains(unknown)));
        assertEquals(2, dry.rows().stream().filter(r -> r.action() == com.financeos.api.nps.dto.NpsImportAction.UNMAPPED).count());
        assertEquals(4, dry.rows().stream().filter(r -> r.action() == com.financeos.api.nps.dto.NpsImportAction.ADD).count());
        assertEquals(0, count("nps_statement", acc));
        assertEquals(0, count("nps_entry", acc));

        NpsImportResultResponse r = apply(acc, renamed);
        assertEquals(2, r.added());
        assertEquals(List.of(unknown), r.unmapped());
        assertEquals(Map.of(POP_E, new BigDecimal("4508.9644"), POP_C, new BigDecimal("2157.5713")), units(acc));
        assertFalse(r.unitsMismatch());
        assertEquals(2, count("nps_allocation", acc));
    }

    @Test
    void aManualEntryThatBreaksTheClosingFlagsTheMismatchOnTheSummary() throws Exception {
        UUID acc = freshAccount();
        api.postJson(BASE + "/entries", Map.of("accountId", acc.toString(), "tier", "I", "type", "CONTRIBUTION",
                "amount", 100, "postedDate", "2025-01-20",
                "lines", List.of(Map.of("schemeCode", POP_E, "units", new BigDecimal("10"), "nav", new BigDecimal("10")))), 201);
        assertFalse(api.getJson(BASE + "/summary", 200).get("flags").get("unitsMismatch").asBoolean());

        NpsImportResultResponse r = apply(acc, "wide-contribution");

        assertTrue(r.unitsMismatch());
        assertTrue(jdbc.queryForObject("SELECT units_mismatch FROM nps_statement WHERE id = ?", Boolean.class, r.statementId().toString()));
        assertTrue(api.getJson(BASE + "/summary", 200).get("flags").get("unitsMismatch").asBoolean());
        // deleting the statement clears it
        api.deleteJson(IMPORT + "/statements/" + r.statementId(), 204);
        assertFalse(api.getJson(BASE + "/summary", 200).get("flags").get("unitsMismatch").asBoolean());
    }

    // ------------------------------------------------------------------ scheme names

    @Test
    void schemeNamesMapByFeedNameThenByFundHouseClassTierAndVariant() {
        assertEquals("HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E TIER I DIRECT",
                NpsSchemeResolver.normalize("NPS Trust A/C HDFC Pension Fund Management Ltd., Scheme E - Tier I Direct"));
        Map<String, String> m = resolver.resolve(List.of(
                "HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME E - TIER I",
                "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME G - TIER I DIRECT",
                "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LTD SCHEME C - TIER I DIRECT",
                // not the feed's name, but the same fund house, class, tier and variant as SM008002
                "HDFC PENSION MANAGEMENT LIMITED SCHEME C - TIER I",
                "ICICI PRUDENTIAL PENSION FUND SCHEME E - TIER I",
                "HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME A - TIER I",
                "HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME E - TIER II"));
        assertEquals(POP_E, m.get("HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME E - TIER I"));
        assertEquals(DIR_G, m.get("NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME G - TIER I DIRECT"));
        assertEquals(DIR_C, m.get("NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LTD SCHEME C - TIER I DIRECT"));
        assertEquals(POP_C, m.get("HDFC PENSION MANAGEMENT LIMITED SCHEME C - TIER I"));
        assertNull(m.get("ICICI PRUDENTIAL PENSION FUND SCHEME E - TIER I"));
        assertNull(m.get("HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME A - TIER I"));
        assertNull(m.get("HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME E - TIER II"));
    }

    @Test
    void aPopNameMapsToThePlainSchemeWhenNoPopSchemeExists() {
        assertEquals("HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E TIER I POP",
                NpsSchemeResolver.normalize("NPS TRUST- A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I POP"));
        Map<String, String> m = resolver.resolve(List.of(
                "NPS TRUST- A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I POP",
                "NPS TRUST- A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME C - TIER II POP"));
        assertEquals(POP_E, m.get("NPS TRUST- A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I POP"));
        assertNull(m.get("NPS TRUST- A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME C - TIER II POP"));
    }

    // ------------------------------------------------------------------ overlapping statements

    private static final String N_E = "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT";
    private static final String N_C = "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME C - TIER I DIRECT";
    private static final String N_G = "NPS TRUST A/C HDFC PENSION FUND MANAGEMENT LIMITED SCHEME G - TIER I DIRECT";

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static ParsedNpsStatement.Txn rebalance(String scheme, LocalDate date, String units, String amount) {
        boolean zero = bd(units).signum() == 0;
        return new ParsedNpsStatement.Txn(scheme, date, "Rebalancing", NpsTxnKind.REBALANCE, bd(amount),
                zero ? null : bd("100.0000"), bd(units), null);
    }

    /** A complete statement of the three DIRECT schemes with the given opening and closing units. */
    private static ParsedNpsStatement synth(LocalDate start, LocalDate end, String[] open, String[] close,
                                            List<ParsedNpsStatement.Txn> middle) {
        String[] names = {N_E, N_C, N_G};
        List<ParsedNpsStatement.Txn> txns = new ArrayList<>();
        List<ParsedNpsStatement.Holding> holdings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            txns.add(new ParsedNpsStatement.Txn(names[i], start, "Opening Balance", NpsTxnKind.OPENING, null, null,
                    bd(open[i]), null));
        }
        txns.addAll(middle);
        for (int i = 0; i < 3; i++) {
            txns.add(new ParsedNpsStatement.Txn(names[i], end, "Closing Balance", NpsTxnKind.CLOSING, null, null,
                    bd(close[i]), null));
            holdings.add(new ParsedNpsStatement.Holding(names[i], bd(close[i]), bd("100.0000"), end, null));
        }
        return new ParsedNpsStatement(PRAN, "RAVI KUMAR SHARMA", start, end, ParsedNpsStatement.Layout.STACKED, Map.of(),
                null, List.of(), null, holdings, List.of(), txns, List.of());
    }

    /** The September rebalance as the annual statement and the two monthly ones print it. */
    private List<ParsedNpsStatement> overlapping() {
        LocalDate sep28 = LocalDate.of(2026, 9, 28);
        ParsedNpsStatement annual = synth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 31),
                new String[]{"100.0000", "50.0000", "50.0000"}, new String[]{"90.0000", "54.0000", "56.0000"},
                List.of(rebalance(N_E, sep28, "-10.0000", "-1000.00"), rebalance(N_C, sep28, "4.0000", "400.00"),
                        rebalance(N_G, sep28, "6.0000", "600.00")));
        ParsedNpsStatement sep = synth(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                new String[]{"100.0000", "50.0000", "50.0000"}, new String[]{"90.0000", "50.0000", "50.0000"},
                List.of(rebalance(N_E, sep28, "-10.0000", "-1000.00"), rebalance(N_C, sep28, "0.0000", "0.00"),
                        rebalance(N_G, sep28, "0.0000", "0.00")));
        ParsedNpsStatement oct = synth(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                new String[]{"90.0000", "50.0000", "50.0000"}, new String[]{"90.0000", "54.0000", "56.0000"},
                List.of(rebalance(N_C, LocalDate.of(2026, 10, 4), "4.0000", "400.00"),
                        rebalance(N_G, LocalDate.of(2026, 10, 4), "6.0000", "600.00")));
        return List.of(annual, sep, oct);
    }

    @Test
    void theShortestStatementSuppliesTheEntriesOfItsDatesInAnyImportOrder() throws Exception {
        List<ParsedNpsStatement> s = overlapping(); // annual, September, October
        int[][] orders = {{0, 1, 2}, {2, 1, 0}, {1, 0, 2}, {1, 2, 0}, {0, 2, 1}, {2, 0, 1}};
        List<String> reference = null;
        for (int[] order : orders) {
            UUID acc = freshAccount();
            List<NpsImportResultResponse> results = new ArrayList<>();
            for (int i : order) {
                results.add(apply(acc, s.get(i)));
            }
            assertEquals(Map.of(DIR_E, bd("90.0000"), DIR_C, bd("54.0000"), DIR_G, bd("56.0000")), units(acc),
                    "order " + java.util.Arrays.toString(order));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE account_id = ? AND units_mismatch = TRUE",
                    Integer.class, acc.toString()), "order " + java.util.Arrays.toString(order));
            assertEquals(0, entries(acc, "ADJUSTMENT").size());
            assertEquals(1, entries(acc, "OPENING").size());
            // the September sale and the October purchases are one entry each, nothing double counted
            assertEquals(2, entries(acc, "REBALANCE").size(), "order " + java.util.Arrays.toString(order));
            List<String> set = entrySet(acc);
            if (reference == null) {
                reference = set;
            }
            assertEquals(reference, set, "order " + java.util.Arrays.toString(order));
        }
        // annual first, then the two months: neither month moves the ledger by an adjustment
        UUID acc = freshAccount();
        apply(acc, s.get(0));
        NpsImportResultResponse sep = apply(acc, s.get(1));
        assertTrue(sep.adjustments().isEmpty());
        assertEquals(1, sep.added()); // the E sale; the zero C and G lines are no entry
        NpsImportResultResponse oct = apply(acc, s.get(2));
        assertTrue(oct.adjustments().isEmpty());
        assertFalse(oct.unitsMismatch());
        assertEquals(2, entries(acc, "REBALANCE").size()); // sale on 28 Sep, purchases on 4 Oct
        assertEquals(LocalDate.of(2026, 9, 28), date(entries(acc, "REBALANCE").get(0).get("posted_date")));
        assertEquals(Map.of(DIR_E, bd("-10.0000")), lines(entries(acc, "REBALANCE").get(0).get("id")));
        assertEquals(LocalDate.of(2026, 10, 4), date(entries(acc, "REBALANCE").get(1).get("posted_date")));
    }

    @Test
    void anEntryUpgradedByALongerStatementMovesToTheShorterOnesDate() throws Exception {
        UUID acc = freshAccount();
        NpsEntry estimate = new NpsEntry();
        estimate.setUser(userRepository.getReferenceById(userId));
        estimate.setAccount(accountRepository().getReferenceById(acc));
        estimate.setTier(NpsTier.I);
        estimate.setType(NpsEntryType.CONTRIBUTION);
        estimate.setContributor(NpsContributor.SELF);
        estimate.setAmount(new BigDecimal("5000.00"));
        estimate.setPostedDate(LocalDate.of(2026, 9, 16));
        estimate.setSource(NpsEntrySource.EMAIL);
        estimate.setStatus(NpsEntryStatus.ESTIMATED);
        estimate.addLine(new NpsEntryLine(DIR_E, new BigDecimal("40.0000"), null, null));
        UUID id = entryRepository().saveAndFlush(estimate).getId();
        List<ParsedNpsStatement.Txn> annualTxn = List.of(new ParsedNpsStatement.Txn(N_E, LocalDate.of(2026, 9, 15),
                "By Contribution", NpsTxnKind.CONTRIBUTION, bd("5000.00"), bd("125.0000"), bd("40.0000"), null));
        List<ParsedNpsStatement.Txn> monthTxn = List.of(new ParsedNpsStatement.Txn(N_E, LocalDate.of(2026, 9, 19),
                "By Contribution", NpsTxnKind.CONTRIBUTION, bd("5000.00"), bd("125.0000"), bd("40.0000"), null));
        String[] zero = {"0.0000", "0.0000", "0.0000"};
        String[] forty = {"40.0000", "0.0000", "0.0000"};
        apply(acc, synth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 31), zero, forty, annualTxn));
        assertEquals(LocalDate.of(2026, 9, 15), date(entries(acc, "CONTRIBUTION").get(0).get("posted_date")));
        apply(acc, synth(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), zero, forty, monthTxn));

        List<Map<String, Object>> c = entries(acc, "CONTRIBUTION");
        assertEquals(1, c.size());
        assertEquals(id.toString(), c.get(0).get("id").toString());
        assertEquals(LocalDate.of(2026, 9, 19), date(c.get(0).get("posted_date")));
        assertEquals(Map.of(DIR_E, bd("40.0000")), units(acc));
    }

    // ------------------------------------------------------------------ passwords, preview, commit

    private JsonNode preview(ApiTestClient client, String fixture, String password, UUID accountId, int expected) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        if (password != null) {
            fields.put("password", password);
        }
        if (accountId != null) {
            fields.put("accountId", accountId.toString());
        }
        return client.postMultipart(IMPORT + "/preview", fixture + ".pdf", pdf(fixture), fields, expected);
    }

    @Test
    void aWrongPasswordWithNoProfileAsksForOneAndTheProfileOpensTheFile() throws Exception {
        JsonNode locked = preview(api, "wide-contribution", "nope1234", null, 200);
        assertTrue(locked.get("passwordNeeded").asBoolean());
        assertTrue(locked.get("previewId").isNull());
        assertTrue(preview(api, "wide-contribution", null, null, 200).get("passwordNeeded").asBoolean());

        // the first four letters of the first name and the day and month of birth open it
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setDisplayName("Ravi Kumar Sharma");
        user.setBirthDate("1990-06-14");
        userRepository.saveAndFlush(user);
        JsonNode open = preview(api, "wide-contribution", "wrong-first", null, 200);
        assertFalse(open.get("passwordNeeded").asBoolean());
        assertFalse(open.get("previewId").isNull());
        assertEquals("2025-01-31", open.get("statement").get("periodEnd").asText());
    }

    @Test
    void aPreviewOfAnUnknownPranProposesAnAccountAndWritesNothingUntilCommit() throws Exception {
        JsonNode p = preview(api, "wide-contribution", "ravi1406", null, 200);
        assertFalse(p.get("passwordNeeded").asBoolean());
        assertTrue(p.get("account").get("id").isNull());
        JsonNode proposal = p.get("account").get("proposal");
        assertEquals("RAVI KUMAR SHARMA", proposal.get("holderName").asText());
        assertEquals("XXXX XXXX 5678", proposal.get("pranMasked").asText());
        assertEquals("PROTEAN", proposal.get("cra").asText());
        assertEquals("ALL_CITIZEN", proposal.get("sector").asText()); // the contribution came in through eNPS
        assertEquals("SELF", proposal.get("relation").asText());
        assertFalse(p.toString().contains(PRAN));
        assertEquals("XXXX XXXX 5678", p.get("statement").get("pranMasked").asText());
        assertEquals("AGGRESSIVE CHOICE", p.get("statement").get("schemeChoice").asText());
        JsonNode result = p.get("result");
        assertEquals(2, result.get("added").asInt());
        JsonNode rows = p.get("rows");
        assertEquals(6, rows.size());
        for (JsonNode row : rows) {
            assertEquals("ADD", row.get("action").asText());
            assertFalse(row.get("schemeCode").isNull());
        }
        // the dry run left no statement, entry or account behind
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_account WHERE user_id = ?", Integer.class, userId.toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE user_id = ?", Integer.class, userId.toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_entry WHERE user_id = ?", Integer.class, userId.toString()));

        String previewId = p.get("previewId").asText();
        JsonNode done = api.postJson(IMPORT + "/commit", Map.of("previewId", previewId,
                "newAccount", Map.of("holderName", "Ravi", "relation", "SELF", "sector", "ALL_CITIZEN", "cra", "PROTEAN")), 200);
        assertEquals(2, done.get("added").asInt());
        assertFalse(done.get("unitsMismatch").asBoolean());
        UUID acc = UUID.fromString(jdbc.queryForObject("SELECT id FROM nps_account WHERE user_id = ?", String.class, userId.toString()));
        assertEquals("Ravi", jdbc.queryForObject("SELECT holder_name FROM nps_account WHERE id = ?", String.class, acc.toString()));
        assertEquals(3, entries(acc, "OPENING").isEmpty() ? 0 : lines(entries(acc, "OPENING").get(0).get("id")).size());
        assertEquals(1, count("nps_statement", acc));
        // the file's hash is kept, the PRAN is encrypted and the typed password saved encrypted
        assertEquals(64, jdbc.queryForObject("SELECT LENGTH(file_sha256) FROM nps_statement WHERE account_id = ?", Integer.class, acc.toString()));
        String saved = jdbc.queryForObject("SELECT statement_password_encrypted FROM nps_account WHERE id = ?", String.class, acc.toString());
        assertNotEquals("ravi1406", saved);
        assertFalse(saved.contains("ravi1406"));

        // a preview is spent by its commit
        api.postJson(IMPORT + "/commit", Map.of("previewId", previewId), 404);

        // the same file again, with no password typed: the saved one opens it and the PRAN finds the account
        JsonNode again = preview(api, "wide-contribution", null, null, 200);
        assertFalse(again.get("passwordNeeded").asBoolean());
        assertEquals(acc.toString(), again.get("account").get("id").asText());
        assertEquals(0, again.get("result").get("added").asInt());
        assertEquals(2, again.get("result").get("skipped").asInt());
        assertEquals("SKIP", again.get("rows").get(0).get("action").asText());
        JsonNode listed = api.getJson(IMPORT + "/statements?accountId=" + acc, 200);
        assertEquals(1, listed.size());
        assertEquals("2025-01-01", listed.get(0).get("periodStart").asText());
        assertEquals("UPLOAD", listed.get(0).get("source").asText());
        assertFalse(listed.get(0).get("unitsMismatch").asBoolean());
        assertEquals(0, listed.get(0).get("warningCount").asInt());

        // picking an account that has another PRAN is refused
        UUID other = freshAccount();
        preview(api, "wide-contribution", "ravi1406", other, 400);
    }

    @Test
    void aSecondFileOfANewPranCommitsIntoTheAccountTheFirstCreatedAndTheDtosCarryTheNewFields() throws Exception {
        JsonNode first = preview(api, "wide-contribution", "ravi1406", null, 200);
        JsonNode second = preview(api, "wide-empty", "ravi1406", null, 200);
        assertTrue(second.get("account").get("id").isNull());
        assertEquals("I", first.get("rows").get(0).get("tier").asText());
        Map<String, Object> newAccount = Map.of("holderName", "Ravi", "relation", "SELF", "sector", "ALL_CITIZEN", "cra", "PROTEAN");

        JsonNode one = api.postJson(IMPORT + "/commit", Map.of("previewId", first.get("previewId").asText(), "newAccount", newAccount), 200);
        JsonNode two = api.postJson(IMPORT + "/commit", Map.of("previewId", second.get("previewId").asText(), "newAccount", newAccount), 200);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_account WHERE user_id = ?", Integer.class, userId.toString()));
        String acc = jdbc.queryForObject("SELECT id FROM nps_account WHERE user_id = ?", String.class, userId.toString());
        assertEquals(acc, one.get("accountId").asText());
        assertEquals(acc, two.get("accountId").asText());
        assertEquals(2, count("nps_statement", UUID.fromString(acc)));
        assertEquals(3, one.get("adjustments").size());
        for (JsonNode adj : one.get("adjustments")) {
            assertTrue(adj.get("schemeName").asText().contains("SCHEME"));
        }

        JsonNode matched = preview(api, "wide-contribution", "ravi1406", null, 200).get("account");
        assertEquals(acc, matched.get("id").asText());
        assertEquals("Ravi", matched.get("holderName").asText());
        assertEquals("SELF", matched.get("relation").asText());
        assertEquals("5678", matched.get("pranLast4").asText());
    }

    @Test
    void deletingAStatementRemovesItsEntriesButKeepsUpgradedOnes() throws Exception {
        UUID acc = freshAccount();
        NpsEntry estimate = new NpsEntry();
        estimate.setUser(userRepository.getReferenceById(userId));
        estimate.setAccount(accountRepository().getReferenceById(acc));
        estimate.setTier(NpsTier.I);
        estimate.setType(NpsEntryType.CONTRIBUTION);
        estimate.setAmount(new BigDecimal("37350.00"));
        estimate.setPostedDate(LocalDate.of(2025, 1, 15));
        estimate.setSource(NpsEntrySource.BANK);
        estimate.setStatus(NpsEntryStatus.ESTIMATED);
        estimate.addLine(new NpsEntryLine(POP_E, new BigDecimal("100.0000"), null, null));
        UUID estimateId = entryRepository().saveAndFlush(estimate).getId();
        NpsImportResultResponse jan = apply(acc, "wide-contribution");
        apply(acc, "wide-empty");
        assertEquals(1, jan.upgraded());

        api.deleteJson(IMPORT + "/statements/" + jan.statementId(), 204);

        // the charge and the opening went with the statement; the upgraded contribution stays, units and all
        assertEquals(0, entries(acc, "CHARGE").size());
        assertEquals(0, entries(acc, "OPENING").size());
        List<Map<String, Object>> kept = entries(acc, "CONTRIBUTION");
        assertEquals(1, kept.size());
        assertEquals(estimateId.toString(), kept.get(0).get("id").toString());
        assertEquals("STATEMENT", kept.get(0).get("source"));
        assertNull(kept.get(0).get("statement_id"));
        assertEquals(3, lines(estimateId).size());
        assertEquals(1, count("nps_statement", acc));
        // the Feb statement is now the first, so its opening units come back as the opening entry
        List<Map<String, Object>> opening = entries(acc, "OPENING");
        assertEquals(0, opening.size()); // the ledger holds the contribution's units, so it is an adjustment instead
        assertEquals(1, entries(acc, "ADJUSTMENT").size());
        assertEquals(Map.of(POP_E, new BigDecimal("4508.9644"), POP_C, new BigDecimal("2157.5713"),
                POP_G, new BigDecimal("2604.4642")), units(acc));
        api.deleteJson(IMPORT + "/statements/" + UUID.randomUUID(), 404);
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    void anotherUsersPreviewStatementAndAccountAreNotFound() throws Exception {
        JsonNode p = preview(api, "wide-contribution", "ravi1406", null, 200);
        String previewId = p.get("previewId").asText();
        otherApi.postJson(IMPORT + "/commit", Map.of("previewId", previewId,
                "newAccount", Map.of("holderName", "Mine now")), 404);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE user_id = ?", Integer.class, otherUserId.toString()));

        UUID acc = freshAccount();
        NpsImportResultResponse r = apply(acc, "wide-contribution");
        otherApi.getJson(IMPORT + "/statements?accountId=" + acc, 404);
        assertEquals(0, otherApi.getJson(IMPORT + "/statements", 200).size());
        otherApi.deleteJson(IMPORT + "/statements/" + r.statementId(), 404);
        otherApi.postMultipart(IMPORT + "/preview", "x.pdf", pdf("wide-contribution"),
                Map.of("password", "ravi1406", "accountId", acc.toString()), 404);
        assertEquals(1, count("nps_statement", acc));
        // and the owner's own commit still works
        api.postJson(IMPORT + "/commit", Map.of("previewId", previewId,
                "newAccount", Map.of("holderName", "Ravi")), 200);
    }

    // ------------------------------------------------------------------ catalog search

    @Test
    void theCatalogPickersLeaveNpsSchemesOutUnlessAskedFor() throws Exception {
        assertTrue(instrumentRepository.searchInstrumentsPage("pension", null, PageRequest.of(0, 50)).isEmpty());
        assertEquals(6, instrumentRepository.searchInstrumentsPage("pension", InstrumentType.nps_scheme, PageRequest.of(0, 50)).size());
        assertTrue(instrumentRepository.listAsSeenBy(userId, "pension", null, "asc", PageRequest.of(0, 50)).isEmpty());
        assertEquals(6, instrumentRepository.listAsSeenBy(userId, "pension", InstrumentType.nps_scheme, "asc", PageRequest.of(0, 50)).getTotalElements());
        assertEquals(0, api.getJson("/api/v1/instruments?search=pension", 200).get("totalElements").asInt());
        assertEquals(6, api.getJson("/api/v1/instruments?search=pension&type=nps_scheme", 200).get("totalElements").asInt());
    }

    // ------------------------------------------------------------------ review fixes (S6)

    private UUID bankTxn(String date, int amount) throws Exception {
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank 1234", "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        return api.create("/api/v1/transactions", Map.of("accountId", bank.toString(), "date", date,
                "amount", -amount, "description", "NPS contribution"));
    }

    private UUID contribution(UUID acc, NpsContributor who, LocalDate date, NpsEntrySource source, NpsEntryStatus status) {
        NpsEntry e = new NpsEntry();
        e.setUser(userRepository.getReferenceById(userId));
        e.setAccount(accountRepository().getReferenceById(acc));
        e.setTier(NpsTier.I);
        e.setType(NpsEntryType.CONTRIBUTION);
        e.setContributor(who);
        e.setAmount(new BigDecimal("5000.00"));
        e.setPostedDate(date);
        e.setSource(source);
        e.setStatus(status);
        e.addLine(new NpsEntryLine(DIR_E, new BigDecimal("40.0000"), null, null));
        return entryRepository().saveAndFlush(e).getId();
    }

    @Test
    void aStatementRowUpgradesOnlyAnEntryOfTheSameContributor() throws Exception {
        UUID acc = freshAccount();
        UUID txn = bankTxn("2026-09-16", 5000);
        UUID self = contribution(acc, NpsContributor.SELF, LocalDate.of(2026, 9, 16), NpsEntrySource.EMAIL, NpsEntryStatus.ESTIMATED);
        jdbc.update("UPDATE nps_entry SET transaction_id = ? WHERE id = ?", txn.toString(), self.toString());
        ParsedNpsStatement.Txn employer = new ParsedNpsStatement.Txn(N_E, LocalDate.of(2026, 9, 15), "By Employer Contribution",
                NpsTxnKind.EMPLOYER_CONTRIBUTION, bd("5000.00"), bd("125.0000"), bd("40.0000"), null);
        ParsedNpsStatement.Txn mine = new ParsedNpsStatement.Txn(N_E, LocalDate.of(2026, 9, 15), "By Contribution",
                NpsTxnKind.CONTRIBUTION, bd("5000.00"), bd("125.0000"), bd("40.0000"), null);
        String[] zero = {"0.0000", "0.0000", "0.0000"};
        String[] eighty = {"80.0000", "0.0000", "0.0000"};

        NpsImportResultResponse r = apply(acc, synth(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), zero, eighty,
                List.of(employer, mine)));

        assertEquals(1, r.upgraded());
        assertEquals(1, r.added());
        List<Map<String, Object>> c = entries(acc, "CONTRIBUTION");
        assertEquals(2, c.size());
        Map<String, Object> selfRow = c.stream().filter(m -> "SELF".equals(m.get("contributor"))).findFirst().orElseThrow();
        Map<String, Object> employerRow = c.stream().filter(m -> "EMPLOYER".equals(m.get("contributor"))).findFirst().orElseThrow();
        assertEquals(self.toString(), selfRow.get("id").toString());
        assertEquals(txn.toString(), selfRow.get("transaction_id").toString());
        assertEquals("STATEMENT", selfRow.get("source"));
        assertNull(employerRow.get("transaction_id"));
    }

    @Test
    void aSupersededLinkedEntryHandsItsPaymentAndDismissalsToTheReplacement() throws Exception {
        UUID acc = freshAccount();
        UUID txn = bankTxn("2026-09-15", 5000);
        UUID other = bankTxn("2026-09-18", 70);
        ParsedNpsStatement.Txn annualTxn = new ParsedNpsStatement.Txn(N_E, LocalDate.of(2026, 9, 15),
                "By Contribution", NpsTxnKind.CONTRIBUTION, bd("5000.00"), bd("125.0000"), bd("40.0000"), null);
        ParsedNpsStatement.Txn monthTxn = new ParsedNpsStatement.Txn(N_E, LocalDate.of(2026, 9, 19),
                "By Contribution", NpsTxnKind.CONTRIBUTION, bd("5000.00"), bd("125.0000"), bd("40.0000"), null);
        String[] zero = {"0.0000", "0.0000", "0.0000"};
        String[] forty = {"40.0000", "0.0000", "0.0000"};
        apply(acc, synth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 31), zero, forty, List.of(annualTxn)));
        String oldId = entries(acc, "CONTRIBUTION").get(0).get("id").toString();
        jdbc.update("UPDATE nps_entry SET transaction_id = ? WHERE id = ?", txn.toString(), oldId);
        jdbc.update("INSERT INTO nps_link_dismissal (id, user_id, transaction_id, entry_id, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), userId.toString(), other.toString(), oldId);

        apply(acc, synth(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), zero, forty, List.of(monthTxn)));

        List<Map<String, Object>> c = entries(acc, "CONTRIBUTION");
        assertEquals(1, c.size());
        assertNotEquals(oldId, c.get(0).get("id").toString());
        assertEquals(LocalDate.of(2026, 9, 19), date(c.get(0).get("posted_date")));
        assertEquals(txn.toString(), c.get(0).get("transaction_id").toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_link_dismissal WHERE entry_id = ? AND transaction_id = ?",
                Integer.class, c.get(0).get("id").toString(), other.toString()));
        assertEquals(Map.of(DIR_E, bd("40.0000")), units(acc));
    }

    @Test
    void aSupersededLinkedEntryWithNoReplacementStaysWithoutItsStatement() throws Exception {
        UUID acc = freshAccount();
        UUID txn = bankTxn("2026-09-15", 5000);
        ParsedNpsStatement.Txn annualTxn = new ParsedNpsStatement.Txn(N_E, LocalDate.of(2026, 9, 15),
                "By Contribution", NpsTxnKind.CONTRIBUTION, bd("5000.00"), bd("125.0000"), bd("40.0000"), null);
        String[] zero = {"0.0000", "0.0000", "0.0000"};
        String[] forty = {"40.0000", "0.0000", "0.0000"};
        apply(acc, synth(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 31), zero, forty, List.of(annualTxn)));
        String oldId = entries(acc, "CONTRIBUTION").get(0).get("id").toString();
        jdbc.update("UPDATE nps_entry SET transaction_id = ? WHERE id = ?", txn.toString(), oldId);

        apply(acc, synth(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), zero, zero, List.of()));

        List<Map<String, Object>> c = entries(acc, "CONTRIBUTION");
        assertEquals(1, c.size());
        assertEquals(oldId, c.get(0).get("id").toString());
        assertEquals(txn.toString(), c.get(0).get("transaction_id").toString());
        assertNull(c.get(0).get("statement_id"));
    }

    @Test
    void aCompleteStatementReplacesTheIncompleteOneOfTheSamePeriod() throws Exception {
        ParsedNpsStatement empty = parsed("wide-empty");
        ParsedNpsStatement noFigures = new ParsedNpsStatement(empty.pran(), empty.subscriberName(), empty.periodStart(),
                empty.periodEnd(), empty.layout(), empty.tierStatuses(), empty.schemeChoice(), empty.prefs(),
                empty.summary(), List.of(), empty.contributions(), empty.txns(), List.of());
        UUID acc = freshAccount();
        NpsImportResultResponse incomplete = apply(acc, noFigures);
        assertTrue(incomplete.incomplete());

        NpsImportResultResponse complete = apply(acc, empty);

        assertFalse(complete.incomplete());
        assertNotEquals(incomplete.statementId(), complete.statementId());
        assertFalse(complete.warnings().contains("Already imported"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE id = ?", Integer.class,
                incomplete.statementId().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE account_id = ?", Integer.class, acc.toString()));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement_holding WHERE statement_id = ?", Integer.class,
                complete.statementId().toString()) > 0);
    }

    @Test
    void aManualEntryAddedAfterAStatementSetsTheMismatchAndDeletingItClearsIt() throws Exception {
        UUID acc = freshAccount();
        NpsImportResultResponse r = apply(acc, "wide-contribution");
        assertFalse(r.unitsMismatch());

        JsonNode created = api.postJson(BASE + "/entries", Map.of("accountId", acc.toString(), "tier", "I",
                "type", "CONTRIBUTION", "amount", 100, "postedDate", "2025-01-10",
                "lines", List.of(Map.of("schemeCode", POP_E, "units", new BigDecimal("10"), "nav", new BigDecimal("10")))), 201);

        assertTrue(jdbc.queryForObject("SELECT units_mismatch FROM nps_statement WHERE id = ?", Boolean.class, r.statementId().toString()));

        api.deleteJson(BASE + "/entries/" + created.get("id").asText(), 204);

        assertFalse(jdbc.queryForObject("SELECT units_mismatch FROM nps_statement WHERE id = ?", Boolean.class, r.statementId().toString()));
    }

    @Autowired NpsEntryRepository entryRepositoryBean;
    @Autowired NpsAccountRepository accountRepositoryBean;

    private NpsEntryRepository entryRepository() {
        return entryRepositoryBean;
    }

    private NpsAccountRepository accountRepository() {
        return accountRepositoryBean;
    }

    // ------------------------------------------------------------------ Tier I status

    private ParsedNpsStatement withStatus(ParsedNpsStatement p, String tier1, int monthsLater) {
        Map<String, String> statuses = new java.util.HashMap<>();
        if (tier1 != null) {
            statuses.put("Tier I Status", tier1);
        }
        return new ParsedNpsStatement(p.pran(), p.subscriberName(), p.periodStart().plusMonths(monthsLater),
                p.periodEnd().plusMonths(monthsLater), p.layout(), statuses, p.schemeChoice(), p.prefs(),
                p.summary(), p.holdings(), p.contributions(), p.txns(), p.warnings());
    }

    private Map<String, Object> tier1(UUID acc) {
        return jdbc.queryForMap("SELECT tier1_status, tier1_status_as_of FROM nps_account WHERE id = ?", acc.toString());
    }

    @Test
    void tier1StatusFollowsTheNewestStatementAndIgnoresOlderOnes() throws Exception {
        ParsedNpsStatement base = parsed("wide-empty");
        UUID acc = freshAccount();

        apply(acc, withStatus(base, "PRAN FROZEN FOR NOT MEETING THE MIN", 2));
        assertEquals("FROZEN", tier1(acc).get("TIER1_STATUS"));

        apply(acc, withStatus(base, "ACTIVE", 4));
        assertEquals("ACTIVE", tier1(acc).get("TIER1_STATUS"));

        // an older frozen statement imported afterwards does not overwrite it
        apply(acc, withStatus(base, "PRAN FROZEN", 3));
        assertEquals("ACTIVE", tier1(acc).get("TIER1_STATUS"));

        // a statement without a status changes nothing, even a newer one
        apply(acc, withStatus(base, null, 6));
        assertEquals("ACTIVE", tier1(acc).get("TIER1_STATUS"));
        assertEquals(java.sql.Date.valueOf(base.periodEnd().plusMonths(4)).toString(),
                tier1(acc).get("TIER1_STATUS_AS_OF").toString().substring(0, 10));

        apply(acc, withStatus(base, "CLOSED", 8));
        assertEquals("OTHER", tier1(acc).get("TIER1_STATUS"));
    }
}
