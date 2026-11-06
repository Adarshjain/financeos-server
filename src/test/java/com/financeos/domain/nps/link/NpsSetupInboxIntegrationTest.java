package com.financeos.domain.nps.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.collect.NpsMatchInboxCollector;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
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

/** The "Set up NPS?" Inbox suggestion: shown to a user with no NPS account who pays NPS, over real persistence. */
@SpringBootTest
@AutoConfigureMockMvc
class NpsSetupInboxIntegrationTest {

    private static final String BASE = "/api/v1/nps";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired NpsMatchInboxCollector collector;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private UUID bank;
    private UUID otherBank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "nps-setup-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String other = "nps-setup-other-" + UUID.randomUUID() + "@example.test";
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

    private void debit(ApiTestClient client, UUID account, LocalDate date, int amount, String description) throws Exception {
        client.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", date.toString(),
                "amount", -amount, "description", description));
    }

    private List<InboxItemResponse> setupRows() {
        return UserContextHelper.callAs(userId, () -> collector.collect(userId, AppTime.today())).stream()
                .filter(r -> "nps_setup".equals(r.kind())).toList();
    }

    @Test
    void anNpsPayerWithNoAccountGetsOneSetupRowWithTheCountAndTheLastDate() throws Exception {
        debit(api, bank, TODAY.minusDays(10), 5000, "NPS TRUST");
        debit(api, bank, TODAY.minusDays(40), 5000, "PROTEAN CRA NPS CONTRIBUTION");
        debit(api, bank, TODAY.minusDays(3), 800, "SWIGGY");

        List<InboxItemResponse> rows = setupRows();

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        assertEquals("nps_setup", row.key());
        assertEquals("Looks like you invest in NPS", row.title());
        assertEquals("2 payments to NPS · last 26 Oct 2026", row.subtitle());
        assertEquals("/investments/nps", row.href());
        assertEquals(List.of("open", "nps_setup_not_nps", "dismiss"), row.actions().stream().map(a -> a.type()).toList());
        assertEquals("/investments/nps", row.actions().get(0).href());
        assertEquals(InboxItemResponse.SECTION_NEEDS_LOOK, row.section());
    }

    @Test
    void aSinglePaymentReadsInTheSingular() throws Exception {
        debit(api, bank, TODAY.minusDays(1), 5000, "NPS TRUST");

        assertEquals("1 payment to NPS · last 04 Nov 2026", setupRows().get(0).subtitle());
    }

    @Test
    void nothingWithoutAnNpsLookingDebitInTheLast10Years() throws Exception {
        assertTrue(setupRows().isEmpty());
        debit(api, bank, TODAY.minusYears(10).minusDays(1), 5000, "NPS TRUST");
        debit(api, bank, TODAY.minusDays(5), 1, "ENPS KYC VERIFICATION");
        debit(api, bank, TODAY.minusDays(5), 500, "UPI-SOMEONE");

        assertTrue(setupRows().isEmpty());
        debit(api, bank, TODAY.minusYears(10).plusDays(1), 5000, "NPS TRUST");
        assertEquals(1, setupRows().size());
    }

    @Test
    void theRowStopsOnceAnAccountExistsEvenWhenItIsClosed() throws Exception {
        debit(api, bank, TODAY.minusDays(10), 5000, "NPS TRUST");
        assertEquals(1, setupRows().size());

        UUID account = api.create(BASE + "/accounts", Map.of("pran", "123456789012", "holderName", "Test Holder",
                "relation", "SELF", "sector", "ALL_CITIZEN", "cra", "PROTEAN"));

        assertTrue(setupRows().isEmpty());
        api.putJson(BASE + "/accounts/" + account, Map.of("status", "CLOSED"), 200);
        assertTrue(setupRows().isEmpty());
    }

    @Test
    void notNpsMarksEveryCountedPaymentInOneCallAndHidesTheRow() throws Exception {
        debit(api, bank, TODAY.minusDays(10), 5000, "NPS TRUST");
        debit(api, bank, TODAY.minusDays(40), 5000, "NPS TRUST");
        debit(api, bank, TODAY.minusDays(70), 5000, "NPS TRUST");
        assertEquals(1, setupRows().size());

        api.postJson(BASE + "/setup/not-nps", Map.of(), 204);
        api.postJson(BASE + "/setup/not-nps", Map.of(), 204);

        assertTrue(setupRows().isEmpty());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM nps_link_dismissal WHERE user_id = ? AND entry_id IS NULL",
                Integer.class, userId.toString()));
        // a new NPS payment later brings the suggestion back, counting only that one
        debit(api, bank, TODAY.minusDays(2), 5000, "NPS TRUST");
        assertEquals("1 payment to NPS · last 03 Nov 2026", setupRows().get(0).subtitle());
    }

    @Test
    void anotherUsersPaymentsAreNeitherCountedNorMarked() throws Exception {
        debit(otherApi, otherBank, TODAY.minusDays(10), 5000, "NPS TRUST");
        debit(otherApi, otherBank, TODAY.minusDays(20), 5000, "NPS TRUST");

        assertTrue(setupRows().isEmpty());
        api.postJson(BASE + "/setup/not-nps", Map.of(), 204);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_link_dismissal", Integer.class) - existingDismissals());
        List<InboxItemResponse> others = UserContextHelper.callAs(otherUserId, () -> collector.collect(otherUserId, AppTime.today()));
        assertEquals(1, others.size());
        assertEquals("2 payments to NPS · last 26 Oct 2026", others.get(0).subtitle());
    }

    /** Dismissals of the other user (zero until they act): the user's own call must not have added any. */
    private int existingDismissals() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM nps_link_dismissal WHERE user_id = ?", Integer.class, otherUserId.toString());
    }
}
