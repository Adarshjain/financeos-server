package com.financeos.domain.nps.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.collect.NpsMatchInboxCollector;
import com.financeos.domain.instrument.price.NpsTrustFeedClient;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsCatalogService;
import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsEntry;
import com.financeos.domain.nps.NpsEntryRepository;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsTier;
import com.financeos.domain.obligation.ObligationKind;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.transaction.autolink.NpsAutoLinker;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** NPS payment linking over real persistence: auto-link, Inbox rows, endpoints, dismissals, tenancy. */
@SpringBootTest
@AutoConfigureMockMvc
class NpsLinkIntegrationTest {

    private static final String BASE = "/api/v1/nps";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String EQ = "SMT98001";
    private static final String GOV = "SMT98002";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired NpsCatalogService catalogService;
    @Autowired NpsEntryRepository entryRepository;
    @Autowired NpsAccountRepository accountRepository;
    @Autowired NpsAutoLinker autoLinker;
    @Autowired NpsMatchInboxCollector collector;
    @Autowired ObligationRefService obligationRefs;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private UUID bank;
    private UUID npsAccount;

    @BeforeEach
    void setUp() throws Exception {
        String email = "nps-link-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String other = "nps-link-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, other);
        otherUserId = userRepository.findByEmail(other).orElseThrow().getId();
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        seedCatalog();
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank 1234", "last4", "1234",
                "openingBalance", 1000000, "financialPosition", "asset", "excludeFromNetAsset", false));
        npsAccount = api.create(BASE + "/accounts", Map.of("pran", "123456789012", "holderName", "Test Holder",
                "relation", "SELF", "sector", "ALL_CITIZEN", "cra", "PROTEAN"));
    }

    @AfterEach
    void cleanUp() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
        jdbc.update("DELETE FROM instrument_prices WHERE instrument_id IN "
                + "(SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT98%')");
    }

    @AfterAll
    static void dropCatalog(@Autowired JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM instrument_prices WHERE instrument_id IN (SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT98%')");
        List<String> ids = jdbc.queryForList("SELECT instrument_id FROM nps_scheme WHERE scheme_code LIKE 'SMT98%'", String.class);
        jdbc.update("DELETE FROM nps_scheme WHERE scheme_code LIKE 'SMT98%'");
        ids.forEach(id -> jdbc.update("DELETE FROM instruments WHERE id = ?", id));
    }

    private void seedCatalog() {
        LocalDate older = TODAY.minusDays(30);
        LocalDate newer = TODAY.minusDays(29);
        catalogService.apply(List.of(
                new NpsTrustFeedClient.NavRow(EQ, "TEST PENSION FUND SCHEME E - TIER I DIRECT", "Test PFM", older, new BigDecimal("98.0000")),
                new NpsTrustFeedClient.NavRow(GOV, "TEST PENSION FUND SCHEME G - TIER I DIRECT", "Test PFM", older, new BigDecimal("49.0000"))));
        catalogService.apply(List.of(
                new NpsTrustFeedClient.NavRow(EQ, "TEST PENSION FUND SCHEME E - TIER I DIRECT", "Test PFM", newer, new BigDecimal("100.0000")),
                new NpsTrustFeedClient.NavRow(GOV, "TEST PENSION FUND SCHEME G - TIER I DIRECT", "Test PFM", newer, new BigDecimal("50.0000"))));
    }

    private void allocate() throws Exception {
        api.putJson(BASE + "/accounts/" + npsAccount + "/allocation", Map.of("tier", "I", "choice", "Active",
                "rows", List.of(Map.of("schemeCode", EQ, "percent", 60), Map.of("schemeCode", GOV, "percent", 40))), 200);
    }

    private UUID debit(ApiTestClient client, UUID account, LocalDate date, int amount, String description) throws Exception {
        return client.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", date.toString(),
                "amount", -amount, "description", description));
    }

    private UUID credit(LocalDate date, int amount, String description) throws Exception {
        return api.create("/api/v1/transactions", Map.of("accountId", bank.toString(), "date", date.toString(),
                "amount", amount, "description", description));
    }

    private UUID contribution(UUID user, UUID account, String amount, LocalDate posted) {
        NpsEntry e = new NpsEntry();
        e.setUser(userRepository.getReferenceById(user));
        e.setAccount(accountRepository.getReferenceById(account));
        e.setTier(NpsTier.I);
        e.setType(NpsEntryType.CONTRIBUTION);
        e.setContributor(NpsContributor.SELF);
        e.setAmount(new BigDecimal(amount));
        e.setPostedDate(posted);
        return entryRepository.saveAndFlush(e).getId();
    }

    private String linkedTxn(UUID entry) {
        return jdbc.queryForObject("SELECT transaction_id FROM nps_entry WHERE id = ?", String.class, entry.toString());
    }

    private void runAutoLinker() {
        UserContextHelper.callAs(userId, () -> autoLinker.link(userId));
    }

    private List<InboxItemResponse> rows() {
        return UserContextHelper.callAs(userId, () -> collector.collect(userId, AppTime.today()));
    }

    // ------------------------------------------------------------------ auto-link

    @Test
    void aUniqueFitIsLinkedAutomaticallyAndShowsOnTheTransaction() throws Exception {
        UUID entry = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        UUID txn = debit(api, bank, TODAY.minusDays(4), 1012, "ENPS/987/NPS TRUST");
        debit(api, bank, TODAY.minusDays(4), 1012, "ZOMATO");

        runAutoLinker();

        assertEquals(txn.toString(), linkedTxn(entry));
        List<ObligationRef> refs = UserContextHelper.callAs(userId, () -> obligationRefs.refsFor(txn));
        assertEquals(1, refs.size());
        assertEquals(ObligationKind.NPS_CONTRIBUTION, refs.get(0).kind());
        assertEquals(entry, refs.get(0).id());
        assertEquals(npsAccount, refs.get(0).parentId());
        assertTrue(rows().isEmpty());
        // a linked payment is no longer a candidate for anything else
        UUID second = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        runAutoLinker();
        assertNull(linkedTxn(second));
    }

    @Test
    void aPaymentOutsideTheFeeOrDateWindowIsNotLinked() throws Exception {
        UUID entry = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        debit(api, bank, TODAY.minusDays(4), 1100, "NPS TRUST");   // too much above
        debit(api, bank, TODAY.minusDays(12), 1000, "NPS TRUST");  // too early
        debit(api, bank, TODAY, 1000, "NPS TRUST");                // after the posted date
        runAutoLinker();
        assertNull(linkedTxn(entry));
    }

    @Test
    void twoCandidatesAreNotAutoLinkedAndGiveTwoMatchRows() throws Exception {
        UUID entry = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        UUID a = debit(api, bank, TODAY.minusDays(3), 1000, "RAZORPAY*NPS TRUST");
        UUID b = debit(api, bank, TODAY.minusDays(4), 1010, "ENPS/55/NPS TRUST");

        runAutoLinker();

        assertNull(linkedTxn(entry));
        List<InboxItemResponse> rows = rows();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> "nps_match".equals(r.kind())));
        assertTrue(rows.stream().allMatch(r -> r.title().startsWith("NPS contribution") && r.title().endsWith("is this the payment?")));
        assertEquals(Set2.of(a, b), Set2.of(rows.stream().map(r -> r.refs().transactionId()).toArray(UUID[]::new)));
        assertTrue(rows.stream().allMatch(r -> entry.equals(r.refs().npsEntryId())));
        assertEquals(List.of("nps_link", "nps_dismiss", "dismiss"), rows.get(0).actions().stream().map(x -> x.type()).toList());
        assertTrue(rows.stream().allMatch(r -> "/investments/retirement/nps".equals(r.href())));
    }

    @Test
    void aDismissedPairIsNeverLinkedOrOfferedAgain() throws Exception {
        UUID entry = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        UUID txn = debit(api, bank, TODAY.minusDays(3), 1000, "NPS TRUST");

        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", txn.toString()), 204);
        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", txn.toString()), 204);

        runAutoLinker();
        assertNull(linkedTxn(entry));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_link_dismissal WHERE user_id = ?", Integer.class, userId.toString()));
        assertTrue(rows().stream().noneMatch(r -> "nps_match".equals(r.kind())));
    }

    @Test
    void unlinkingRemembersThePairSoTheLinkerDoesNotPutItBack() throws Exception {
        UUID entry = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        UUID txn = debit(api, bank, TODAY.minusDays(3), 1000, "NPS TRUST");
        runAutoLinker();
        assertEquals(txn.toString(), linkedTxn(entry));

        api.deleteJson(BASE + "/entries/" + entry + "/transaction", 204);
        assertNull(linkedTxn(entry));
        runAutoLinker();
        assertNull(linkedTxn(entry));
    }

    // ------------------------------------------------------------------ endpoints

    @Test
    void linkValidatesTheDirectionAndThatTheTransactionIsFree() throws Exception {
        UUID entry = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        UUID other = contribution(userId, npsAccount, "2000.00", TODAY.minusDays(2));
        UUID refund = credit(TODAY.minusDays(3), 1000, "NPS TRUST REFUND");
        UUID txn = debit(api, bank, TODAY.minusDays(3), 1000, "NPS TRUST");

        assertEquals(400, api.post(BASE + "/entries/" + entry + "/transaction", Map.of("transactionId", refund.toString())).getStatus());
        JsonNode linked = api.postJson(BASE + "/entries/" + entry + "/transaction", Map.of("transactionId", txn.toString()), 200);
        assertEquals(txn.toString(), linked.get("transactionId").asText());
        assertEquals(400, api.post(BASE + "/entries/" + other + "/transaction", Map.of("transactionId", txn.toString())).getStatus());
        assertEquals(400, api.post(BASE + "/entries/" + entry + "/transaction", Map.of("transactionId", txn.toString())).getStatus());
    }

    @Test
    void recordCreatesAnEstimatedBankContributionLinkedToThePayment() throws Exception {
        allocate();
        UUID txn = debit(api, bank, TODAY.minusDays(10), 5000, "ENPS/123/NPS TRUST");

        List<InboxItemResponse> before = rows();
        assertEquals(1, before.size());
        assertEquals("nps_record", before.get(0).kind());
        assertEquals("Record as NPS contribution?", before.get(0).title());
        assertEquals(txn, before.get(0).refs().transactionId());
        assertEquals(List.of("nps_record", "nps_not_nps", "dismiss"), before.get(0).actions().stream().map(x -> x.type()).toList());

        JsonNode created = api.postJson(BASE + "/transactions/" + txn + "/record", Map.of(), 201);
        assertEquals("CONTRIBUTION", created.get("type").asText());
        assertEquals("SELF", created.get("contributor").asText());
        assertEquals("BANK", created.get("source").asText());
        assertEquals("ESTIMATED", created.get("status").asText());
        assertEquals("I", created.get("tier").asText());
        assertEquals(0, new BigDecimal("5000").compareTo(created.get("amount").decimalValue()));
        assertEquals(TODAY.minusDays(10).toString(), created.get("postedDate").asText());
        assertEquals(txn.toString(), created.get("transactionId").asText());
        assertEquals(2, created.get("lines").size());
        assertTrue(rows().isEmpty());
        // the payment is taken now
        assertEquals(400, api.post(BASE + "/transactions/" + txn + "/record", Map.of()).getStatus());
    }

    @Test
    void recordNeedsAnAccountWhenThereIsNotExactlyOneActiveOwnAccount() throws Exception {
        allocate();
        api.create(BASE + "/accounts", Map.of("pran", "999988887777", "holderName", "Another", "relation", "SELF",
                "sector", "ALL_CITIZEN", "cra", "PROTEAN"));
        UUID txn = debit(api, bank, TODAY.minusDays(10), 5000, "NPS TRUST");
        assertEquals(400, api.post(BASE + "/transactions/" + txn + "/record", Map.of()).getStatus());
        JsonNode created = api.postJson(BASE + "/transactions/" + txn + "/record",
                Map.of("accountId", npsAccount.toString(), "tier", "I"), 201);
        assertEquals(npsAccount.toString(), created.get("accountId").asText());
    }

    @Test
    void notNpsStopsTheRecordRowAndAKycPaymentIsNeverOffered() throws Exception {
        UUID txn = debit(api, bank, TODAY.minusDays(10), 5000, "NPS TRUST");
        debit(api, bank, TODAY.minusDays(10), 1, "ENPS KYC VERIFICATION");
        debit(api, bank, TODAY.minusDays(3), 700, "NPS TRUST"); // not older than 7 days yet

        List<InboxItemResponse> before = rows();
        assertEquals(1, before.size());
        assertEquals(txn, before.get(0).refs().transactionId());

        api.postJson(BASE + "/transactions/" + txn + "/not-nps", Map.of(), 204);
        api.postJson(BASE + "/transactions/" + txn + "/not-nps", Map.of(), 204);

        assertTrue(rows().isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_link_dismissal WHERE user_id = ? AND entry_id IS NULL",
                Integer.class, userId.toString()));
    }

    @Test
    void aPaymentThatFitsAContributionIsNotOfferedAsARecord() throws Exception {
        contribution(userId, npsAccount, "5000.00", TODAY.minusDays(10));
        UUID a = debit(api, bank, TODAY.minusDays(12), 5000, "NPS TRUST");
        UUID b = debit(api, bank, TODAY.minusDays(11), 5000, "NPS TRUST");
        List<InboxItemResponse> rows = rows();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> "nps_match".equals(r.kind())));
        assertEquals(Set2.of(a, b), Set2.of(rows.stream().map(r -> r.refs().transactionId()).toArray(UUID[]::new)));
    }

    // ------------------------------------------------------------------ scope

    @Test
    void rowsExistOnlyForAUserWithAnNpsAccount() throws Exception {
        String email = "nps-link-none-" + UUID.randomUUID() + "@example.test";
        ApiTestClient none = ApiTestClient.signUp(mockMvc, mapper, email);
        UUID noneId = userRepository.findByEmail(email).orElseThrow().getId();
        try {
            UUID acc = none.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "B", "last4", "1111",
                    "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
            debit(none, acc, TODAY.minusDays(10), 5000, "NPS TRUST");
            // no match / record rows (the "Set up NPS?" suggestion is the one row such a user gets, see NpsSetupInboxIntegrationTest)
            assertTrue(UserContextHelper.callAs(noneId, () -> collector.collect(noneId, AppTime.today())).stream()
                    .noneMatch(r -> "nps_match".equals(r.kind()) || "nps_record".equals(r.kind())));
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(noneId));
        }
    }

    @Test
    void anotherUsersEntryAndTransactionAreNotFound() throws Exception {
        UUID entry = contribution(userId, npsAccount, "1000.00", TODAY.minusDays(2));
        UUID txn = debit(api, bank, TODAY.minusDays(3), 1000, "NPS TRUST");
        UUID otherBank = otherApi.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "B", "last4", "2222",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID foreignTxn = debit(otherApi, otherBank, TODAY.minusDays(3), 1000, "NPS TRUST");

        // the other user acts on my entry / transaction
        assertEquals(404, otherApi.post(BASE + "/entries/" + entry + "/transaction", Map.of("transactionId", foreignTxn.toString())).getStatus());
        assertEquals(404, otherApi.post(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", foreignTxn.toString())).getStatus());
        otherApi.deleteJson(BASE + "/entries/" + entry + "/transaction", 404);
        assertEquals(404, otherApi.post(BASE + "/transactions/" + txn + "/record", Map.of()).getStatus());
        assertEquals(404, otherApi.post(BASE + "/transactions/" + txn + "/not-nps", Map.of()).getStatus());
        // I act with the other user's transaction
        assertEquals(404, api.post(BASE + "/entries/" + entry + "/transaction", Map.of("transactionId", foreignTxn.toString())).getStatus());
        assertEquals(404, api.post(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", foreignTxn.toString())).getStatus());
        assertEquals(404, api.post(BASE + "/transactions/" + foreignTxn + "/record", Map.of()).getStatus());
        assertEquals(404, api.post(BASE + "/transactions/" + foreignTxn + "/not-nps", Map.of()).getStatus());
        assertNull(linkedTxn(entry));
    }

    /** Tiny order-free set helper for the assertions above. */
    private static final class Set2 {
        static java.util.Set<UUID> of(UUID... ids) {
            return new java.util.HashSet<>(java.util.Arrays.asList(ids));
        }
    }
}
