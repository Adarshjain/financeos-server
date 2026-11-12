package com.financeos.domain.nps.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsEntry;
import com.financeos.domain.nps.NpsEntryRepository;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsTier;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/v1/nps/review over real persistence: ambiguous matches, unrecorded payments, setup, dismissals, tenancy. */
@SpringBootTest
@AutoConfigureMockMvc
class NpsReviewIntegrationTest {

    private static final String BASE = "/api/v1/nps";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired NpsEntryRepository entryRepository;
    @Autowired NpsAccountRepository accountRepository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private UUID bank;
    private UUID otherBank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "nps-review-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String other = "nps-review-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, other);
        otherUserId = userRepository.findByEmail(other).orElseThrow().getId();
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        bank = bank(api);
        otherBank = bank(otherApi);
    }

    @AfterEach
    void cleanUp() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    private static UUID bank(ApiTestClient client) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank 1234", "last4", "1234",
                "openingBalance", 1000000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID npsAccount(ApiTestClient client, String pran) throws Exception {
        return client.create(BASE + "/accounts", Map.of("pran", pran, "holderName", "Test Holder", "relation", "SELF",
                "sector", "ALL_CITIZEN", "cra", "PROTEAN"));
    }

    private UUID debit(ApiTestClient client, UUID account, LocalDate date, int amount, String description) throws Exception {
        return client.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", date.toString(),
                "amount", -amount, "description", description));
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

    private JsonNode review() throws Exception {
        return api.getJson(BASE + "/review", 200);
    }

    @Test
    void anEntryThatFitsTwoPaymentsListsBothWithTheReason() throws Exception {
        UUID account = npsAccount(api, "100000000001");
        UUID entry = contribution(userId, account, "1000.00", TODAY.minusDays(2));
        UUID a = debit(api, bank, TODAY.minusDays(3), 1000, "RAZORPAY*NPS TRUST");
        UUID b = debit(api, bank, TODAY.minusDays(4), 1010, "ENPS/55/NPS TRUST");

        JsonNode matches = review().get("matches");

        assertEquals(1, matches.size());
        JsonNode m = matches.get(0);
        assertEquals(entry.toString(), m.get("contribution").get("id").asText());
        assertEquals("0001", m.get("contribution").get("account").get("pranLast4").asText());
        assertEquals("Test Holder", m.get("contribution").get("account").get("holderName").asText());
        assertEquals("I", m.get("contribution").get("tier").asText());
        assertEquals("2026-11-03", m.get("contribution").get("date").asText());
        assertEquals(0, new BigDecimal("1000").compareTo(m.get("contribution").get("amount").decimalValue()));
        assertEquals("MULTIPLE_CANDIDATES", m.get("why").asText());
        assertEquals(2, m.get("candidates").size());
        List<String> ids = List.of(m.get("candidates").get(0).get("transactionId").asText(),
                m.get("candidates").get(1).get("transactionId").asText());
        assertTrue(ids.contains(a.toString()) && ids.contains(b.toString()));
        assertEquals("Bank 1234", m.get("candidates").get(0).get("accountName").asText());
    }

    @Test
    void aPaymentSharedByTwoEntriesGivesTheSharedReason() throws Exception {
        UUID account = npsAccount(api, "100000000002");
        contribution(userId, account, "1000.00", TODAY.minusDays(2));
        contribution(userId, account, "1000.00", TODAY.minusDays(1));
        debit(api, bank, TODAY.minusDays(3), 1000, "NPS TRUST");

        JsonNode matches = review().get("matches");

        assertEquals(2, matches.size());
        assertEquals("SHARED_PAYMENT", matches.get(0).get("why").asText());
        assertEquals(1, matches.get(0).get("candidates").size());
    }

    @Test
    void anOldUnmatchedPaymentIsUnrecordedWithItsKeywordAgeAndAccounts() throws Exception {
        UUID account = npsAccount(api, "100000000003");
        UUID txn = debit(api, bank, TODAY.minusDays(20), 5000, "PROTEAN CRA CONTRIBUTION");
        debit(api, bank, TODAY.minusDays(2), 700, "NPS TRUST"); // inside the window: not offered yet

        JsonNode rows = review().get("unrecorded");

        assertEquals(1, rows.size());
        JsonNode u = rows.get(0);
        assertEquals(txn.toString(), u.get("payment").get("transactionId").asText());
        assertEquals("PROTEAN", u.get("matchedOn").asText());
        assertEquals(20, u.get("daysSince").asInt());
        assertEquals(1, u.get("recordTo").size());
        assertEquals(account.toString(), u.get("recordTo").get(0).get("id").asText());
        assertEquals("I", u.get("recordTo").get(0).get("tiers").get(0).asText());
        assertTrue(review().get("setup").isNull());
    }

    @Test
    void aUserWithNoAccountGetsTheSetupBlock() throws Exception {
        for (int i = 0; i < 6; i++) {
            debit(api, bank, TODAY.minusDays(10 + i * 30), 5000, "NPS TRUST");
        }
        debit(api, bank, TODAY.minusDays(3), 800, "SWIGGY");

        JsonNode review = review();

        assertTrue(review.get("matches").isEmpty());
        assertTrue(review.get("unrecorded").isEmpty());
        JsonNode setup = review.get("setup");
        assertEquals(6, setup.get("paymentCount").asInt());
        assertEquals("2026-05-29", setup.get("firstDate").asText());
        assertEquals("2026-10-26", setup.get("lastDate").asText());
        assertEquals(0, new BigDecimal("30000").compareTo(setup.get("totalAmount").decimalValue()));
        assertEquals(5, setup.get("sample").size());
        assertEquals("2026-10-26", setup.get("sample").get(0).get("date").asText());
    }

    @Test
    void dismissedPairsAndNotNpsPaymentsAreHidden() throws Exception {
        UUID account = npsAccount(api, "100000000004");
        UUID entry = contribution(userId, account, "1000.00", TODAY.minusDays(2));
        UUID a = debit(api, bank, TODAY.minusDays(3), 1000, "NPS TRUST");
        UUID b = debit(api, bank, TODAY.minusDays(4), 1005, "ENPS NPS TRUST");
        UUID old = debit(api, bank, TODAY.minusDays(30), 3000, "NPS TRUST");

        api.postJson(BASE + "/entries/" + entry + "/suggestion/dismiss", Map.of("transactionId", a.toString()), 204);
        api.postJson(BASE + "/transactions/" + old + "/not-nps", Map.of(), 204);
        api.postJson(BASE + "/transactions/" + b + "/not-nps", Map.of(), 204);

        JsonNode review = review();
        assertTrue(review.get("matches").isEmpty());
        assertTrue(review.get("unrecorded").isEmpty());
    }

    @Test
    void setupIsHiddenOnceMarkedNotNps() throws Exception {
        debit(api, bank, TODAY.minusDays(10), 5000, "NPS TRUST");
        api.postJson(BASE + "/setup/not-nps", Map.of(), 204);

        assertTrue(review().get("setup").isNull());
    }

    @Test
    void anotherUsersDataNeverAppears() throws Exception {
        UUID account = npsAccount(otherApi, "100000000005");
        contribution(otherUserId, account, "1000.00", TODAY.minusDays(2));
        debit(otherApi, otherBank, TODAY.minusDays(3), 1000, "NPS TRUST");
        debit(otherApi, otherBank, TODAY.minusDays(30), 2000, "NPS TRUST");

        JsonNode mine = review();
        assertTrue(mine.get("matches").isEmpty());
        assertTrue(mine.get("unrecorded").isEmpty());
        assertTrue(mine.get("setup").isNull());
        assertEquals(1, otherApi.getJson(BASE + "/review", 200).get("unrecorded").size());
    }
}
