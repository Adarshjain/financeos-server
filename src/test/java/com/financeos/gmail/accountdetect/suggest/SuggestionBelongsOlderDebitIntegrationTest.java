package com.financeos.gmail.accountdetect.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import com.financeos.gmail.ingest.ParkedMessageSettler;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** An old (closed) debit card from "belongs to", with and without the user's dates (pastCard). Made-up data. */
@SpringBootTest
@AutoConfigureMockMvc
class SuggestionBelongsOlderDebitIntegrationTest {

    private static final String BASE = "/api/v1/account-suggestions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountSuggestionService service;
    @Autowired private AccountSuggestionRepository repository;
    @SpyBean private ParkedMessageSettler settler;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "belongs-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "belongs-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        clearInvocations(settler);
    }

    @AfterEach
    void tearDown() {
        reset(settler);
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private UUID creditCard(ApiTestClient client, String bank, String last4) throws Exception {
        return client.create("/api/v1/accounts", new HashMap<>(Map.of("type", "credit_card", "name", "Card " + bank,
                "last4", last4, "creditLimit", 100000, "anniversaryDate", "2026-01-15", "financialPosition", "liability",
                "excludeFromNetAsset", false, "bank", bank)));
    }

    private UUID bankAccount(ApiTestClient client, String bank, String last4) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "bank_account", "name", "Savings " + bank, "openingBalance", 0,
                "financialPosition", "asset", "excludeFromNetAsset", false, "bank", bank));
        if (last4 != null) body.put("last4", last4);
        return client.create("/api/v1/accounts", body);
    }

    private UUID suggest(String bank, String last4, Kind kind) {
        return suggest(entity(bank, last4, kind, Conf.HIGH), bank + ":" + (kind == Kind.ACCOUNT ? "BANK_ACCOUNT" : kind == Kind.DEBIT_CARD ? "DEBIT_CARD" : "CREDIT_CARD") + ":" + last4);
    }

    private UUID suggest(Detection.Entity e, String key) {
        service.apply(userId, detection(e));
        return row(key).getId();
    }

    private AccountSuggestion row(String key) {
        return repository.findByUserId(userId).stream().filter(s -> s.getKey().equals(key)).findFirst().orElseThrow();
    }

    private JsonNode belongs(UUID id, UUID accountId, String as, int expected, Object... extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("accountId", accountId, "as", as));
        for (int i = 0; i < extra.length; i += 2) body.put((String) extra[i], extra[i + 1]);
        return api.postJson(BASE + "/" + id + "/belongs", body, expected);
    }

    private List<Map<String, Object>> cards(UUID accountId) {
        return jdbc.queryForList("select c.last4 as last4, c.closed_on as closed_on, c.cardholder_id as holder, h.role as role, h.relationship as rel,"
                + " h.person_name as name from cards c join cardholders h on h.id = c.cardholder_id where c.account_id = ? order by c.last4", accountId.toString());
    }

    @Test
    void debitCardAsOlderNumberIsSavedClosedWithTheMailDates() throws Exception {
        UUID account = bankAccount(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "3003", Kind.DEBIT_CARD);

        belongs(sid, account, "OLDER_NUMBER", 204);

        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("last4")).isEqualTo("3003");
        assertThat(cards.get(0).get("closed_on").toString()).startsWith("2026-10-20");   // the suggestion's last-seen date
        assertThat(cards.get(0).get("role")).isEqualTo("PRIMARY");
        AccountSuggestion s = repository.findById(sid).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.BELONGS);
        assertThat(s.getResolvedAccountId()).isEqualTo(account);
        assertThat(s.getResolvedCardId()).isNotNull();
    }

    @Test
    void pastCardDatesAndHolderAreUsed() throws Exception {
        UUID account = bankAccount(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "3003", Kind.DEBIT_CARD);

        belongs(sid, account, "OLDER_NUMBER", 204, "pastCard", Map.of("issuedOn", "2024-02-03", "closedOn", "2025-04-05"));

        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("closed_on").toString()).startsWith("2025-04-05");
        assertThat(jdbc.queryForObject("select issued_on from cards where account_id = ?", java.sql.Date.class, account.toString()).toString())
                .isEqualTo("2024-02-03");
        assertThat(repository.findById(sid).orElseThrow().getResolvedCardId()).isNotNull();
    }

    @Test
    void pastCardWithAMissingDateIsRefusedAndTheSuggestionStaysNew() throws Exception {
        UUID account = bankAccount(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "3003", Kind.DEBIT_CARD);

        belongs(sid, account, "OLDER_NUMBER", 400, "pastCard", Map.of("issuedOn", "2024-02-03"));

        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(cards(account)).isEmpty();
    }

    @Test
    void refusedWhenAnotherSameBankAccountHoldsTheDigits() throws Exception {
        UUID account = bankAccount(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "3003", Kind.DEBIT_CARD);
        creditCard(api, "hdfc", "3003");   // added after the suggestion, so the row is still NEW

        assertThat(belongs(sid, account, "OLDER_NUMBER", 400).toString()).contains("already belongs to");
        assertThat(belongs(sid, account, "OLDER_NUMBER", 400, "pastCard", Map.of("issuedOn", "2024-02-03", "closedOn", "2025-04-05")).toString())
                .contains("already belongs to");

        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(cards(account)).isEmpty();
    }

    @Test
    void bankAccountNumberCannotBeAnOldCard() throws Exception {
        UUID account = bankAccount(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "4444", Kind.ACCOUNT);

        assertThat(belongs(sid, account, "OLDER_NUMBER", 400).toString()).contains("can't be an old card");
        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
    }

    @Test
    void creditCardOlderNumberIsUnchanged() throws Exception {
        UUID account = creditCard(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "2222", Kind.CREDIT_CARD);

        belongs(sid, account, "OLDER_NUMBER", 204);

        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(2);
        assertThat(cards.get(1).get("last4")).isEqualTo("2222");
        assertThat(cards.get(1).get("closed_on").toString()).startsWith("2026-10-20");
    }

    @Test
    void creditCardSuggestionStillCannotBeAnOldCardOnABankAccount() throws Exception {
        UUID account = bankAccount(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "2222", Kind.CREDIT_CARD);

        belongs(sid, account, "OLDER_NUMBER", 400);
    }
}
