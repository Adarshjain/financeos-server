package com.financeos.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionRepository;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** Creating an account or adding a card from an account suggestion marks it ADDED, in the same transaction. Made-up data. */
@SpringBootTest
@AutoConfigureMockMvc
class CreateFromSuggestionIntegrationTest {

    private static final String BASE = "/api/v1/account-suggestions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountSuggestionService suggestions;
    @Autowired private AccountSuggestionRepository repository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @SpyBean private ParkedMessageSettler settler;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "from-sugg-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "from-sugg-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        clearInvocations(settler);
    }

    @AfterEach
    void tearDown() {
        reset(settler);
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private AccountSuggestion row(UUID user, String key) {
        return repository.findByUserId(user).stream().filter(s -> s.getKey().equals(key)).findFirst().orElseThrow();
    }

    private static Detection.Entity addonOf(String last4, String primary) {
        Detection.Entity addon = entity("hdfc", last4, Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.MEDIUM;
        addon.linkedTo = primary;
        return addon;
    }

    private Map<String, Object> creditCard(String last4, UUID suggestionId) {
        Map<String, Object> body = new HashMap<>(Map.of("type", "credit_card", "name", "Suggested Card", "last4", last4,
                "creditLimit", 100000, "anniversaryDate", "2026-01-15", "financialPosition", "liability",
                "excludeFromNetAsset", false, "bank", "hdfc"));
        if (suggestionId != null) body.put("fromSuggestionId", suggestionId);
        return body;
    }

    private long accountCount(UUID user) {
        return jdbc.queryForObject("select count(*) from accounts where user_id = ?", Long.class, user);
    }

    private UUID cardId(UUID accountId, String last4) throws Exception {
        for (JsonNode h : api.getJson("/api/v1/accounts/" + accountId + "/cardholders", 200)) {
            for (JsonNode c : h.get("cards")) {
                if (last4.equals(c.get("last4").asText())) return UUID.fromString(c.get("id").asText());
            }
        }
        throw new AssertionError("no card " + last4);
    }

    @Test
    void creatingFromASuggestionMarksItAddedWithAccountAndCardAndHidesIt() throws Exception {
        suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        UUID sid = row(userId, "hdfc:CREDIT_CARD:2222").getId();
        assertThat(api.getJson(BASE, 200).get("items")).hasSize(1);

        UUID accountId = api.create("/api/v1/accounts", creditCard("2222", sid));

        AccountSuggestion s = repository.findById(sid).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.ADDED);
        assertThat(s.getResolvedAccountId()).isEqualTo(accountId);
        assertThat(s.getResolvedCardId()).isEqualTo(cardId(accountId, "2222"));
        assertThat(api.getJson(BASE, 200).get("items")).isEmpty();
    }

    @Test
    void bankAccountWithoutADebitCardResolvesNoCard() throws Exception {
        suggestions.apply(userId, detection(entity("icici", "1111", Kind.ACCOUNT, Conf.HIGH)));
        UUID sid = row(userId, "icici:BANK_ACCOUNT:1111").getId();

        UUID accountId = api.create("/api/v1/accounts", new HashMap<>(Map.of("type", "bank_account", "name", "Savings",
                "openingBalance", 0, "last4", "1111", "bank", "icici", "financialPosition", "asset",
                "excludeFromNetAsset", false, "fromSuggestionId", sid)));

        AccountSuggestion s = repository.findById(sid).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.ADDED);
        assertThat(s.getResolvedAccountId()).isEqualTo(accountId);
        assertThat(s.getResolvedCardId()).isNull();
    }

    @Test
    void childSuggestionsWhoseCardsAreInTheRequestBecomeAddedToo() throws Exception {
        suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH), addonOf("8888", "hdfc:2222"),
                addonOf("9999", "hdfc:2222")));
        UUID parent = row(userId, "hdfc:CREDIT_CARD:2222").getId();
        assertThat(row(userId, "hdfc:CREDIT_CARD:8888").getParentSuggestionId()).isEqualTo(parent);

        Map<String, Object> body = creditCard("2222", parent);
        body.put("addonCards", List.of(Map.of("last4", "8888", "relationship", "SPOUSE")));
        UUID accountId = api.create("/api/v1/accounts", body);

        AccountSuggestion child = row(userId, "hdfc:CREDIT_CARD:8888");
        assertThat(child.getState()).isEqualTo(State.ADDED);
        assertThat(child.getResolvedAccountId()).isEqualTo(accountId);
        assertThat(child.getResolvedCardId()).isEqualTo(cardId(accountId, "8888"));
        assertThat(row(userId, "hdfc:CREDIT_CARD:9999").getState()).isEqualTo(State.NEW);   // not in the request
    }

    @Test
    void anotherUsersSuggestionIsRejectedAndNothingIsCreated() throws Exception {
        suggestions.apply(otherId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        UUID theirs = row(otherId, "hdfc:CREDIT_CARD:2222").getId();

        JsonNode err = api.postJson("/api/v1/accounts", creditCard("2222", theirs), 400);

        assertThat(err.toString()).doesNotContain(theirs.toString());
        assertThat(accountCount(userId)).isZero();
        assertThat(repository.findById(theirs).orElseThrow().getState()).isEqualTo(State.NEW);
        api.postJson("/api/v1/accounts", creditCard("2222", UUID.randomUUID()), 400);
        assertThat(accountCount(userId)).isZero();
    }

    @Test
    void aSuggestionThatIsNotNewIsRejectedAndNothingIsCreated() throws Exception {
        suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        UUID sid = row(userId, "hdfc:CREDIT_CARD:2222").getId();
        suggestions.notMine(userId, sid);

        JsonNode err = api.postJson("/api/v1/accounts", creditCard("2222", sid), 400);

        assertThat(err.toString()).contains("This suggestion was already handled");
        assertThat(accountCount(userId)).isZero();
    }

    @Test
    void addonAddedToAnExistingAccountFromAnAddonSuggestionBecomesAdded() throws Exception {
        UUID accountId = api.create("/api/v1/accounts", creditCard("2222", null));
        suggestions.apply(userId, detection(addonOf("8888", "hdfc:2222")));
        AccountSuggestion s = row(userId, "hdfc:CREDIT_CARD:8888");
        assertThat(s.getKind()).isEqualTo(AccountSuggestion.Kind.ADDON_CARD);
        assertThat(s.getParentAccountId()).isEqualTo(accountId);

        api.postJson("/api/v1/accounts/" + accountId + "/cardholders", Map.of("personName", "Asha",
                "relationship", "SPOUSE", "last4", "8888", "fromSuggestionId", s.getId()), 201);

        AccountSuggestion after = repository.findById(s.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(State.ADDED);
        assertThat(after.getResolvedAccountId()).isEqualTo(accountId);
        assertThat(after.getResolvedCardId()).isEqualTo(cardId(accountId, "8888"));
    }

    @Test
    void aSuggestionForAnotherAccountIsRejected() throws Exception {
        UUID accountId = api.create("/api/v1/accounts", creditCard("2222", null));
        Map<String, Object> second = creditCard("3333", null);
        second.put("name", "Second");
        UUID secondId = api.create("/api/v1/accounts", second);
        suggestions.apply(userId, detection(addonOf("8888", "hdfc:2222")));
        UUID sid = row(userId, "hdfc:CREDIT_CARD:8888").getId();

        api.postJson("/api/v1/accounts/" + secondId + "/cardholders", Map.of("relationship", "SPOUSE", "last4", "8888",
                "fromSuggestionId", sid), 400);

        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(api.getJson("/api/v1/accounts/" + secondId + "/cardholders", 200)).hasSize(1);
        assertThat(accountId).isNotEqualTo(secondId);
    }

    @Test
    void replacementCardFromASuggestionBecomesAdded() throws Exception {
        UUID accountId = api.create("/api/v1/accounts", creditCard("2222", null));
        Detection.Entity replacement = entity("hdfc", "7777", Kind.CREDIT_CARD, Conf.MEDIUM);
        replacement.lifecycle = Lifecycle.REPLACEMENT;
        replacement.lifecycleConf = Conf.MEDIUM;
        replacement.linkedTo = "hdfc:2222";
        suggestions.apply(userId, detection(replacement));
        AccountSuggestion s = row(userId, "hdfc:CREDIT_CARD:7777");
        assertThat(s.getKind()).isEqualTo(AccountSuggestion.Kind.REPLACEMENT_CARD);

        JsonNode holder = api.getJson("/api/v1/accounts/" + accountId + "/cardholders", 200).get(0);
        UUID old = cardId(accountId, "2222");
        api.postJson("/api/v1/accounts/" + accountId + "/cardholders/" + holder.get("id").asText() + "/cards/" + old + "/replace",
                Map.of("newLast4", "7777", "fromSuggestionId", s.getId()), 200);

        AccountSuggestion after = repository.findById(s.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(State.ADDED);
        assertThat(after.getResolvedCardId()).isEqualTo(cardId(accountId, "7777"));
    }

    @Test
    void parkedMailIsSettledAfterTheCreateCommitsAndOnlyWhenFromASuggestion() throws Exception {
        api.create("/api/v1/accounts", creditCard("4444", null));
        verify(settler, never()).settleAllInNewTransaction(any());

        suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        api.create("/api/v1/accounts", creditCard("2222", row(userId, "hdfc:CREDIT_CARD:2222").getId()));

        verify(settler).settleAllInNewTransaction(userId);
    }

    @Test
    void aSettleFailureNeverFailsOrRollsBackTheCreate() throws Exception {
        doThrow(new IllegalStateException("boom")).when(settler).settleAllInNewTransaction(any());
        suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        UUID sid = row(userId, "hdfc:CREDIT_CARD:2222").getId();

        UUID accountId = api.create("/api/v1/accounts", creditCard("2222", sid));

        verify(settler).settleAllInNewTransaction(userId);
        assertThat(accountCount(userId)).isEqualTo(1);
        assertThat(repository.findById(sid).orElseThrow().getResolvedAccountId()).isEqualTo(accountId);
    }

    // ---------------------------------------------------------------- atomic claim, card matching, existing-account flows

    @Test
    void aCreateThatFailsAfterTheClaimLeavesTheSuggestionNew() throws Exception {
        suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        UUID sid = row(userId, "hdfc:CREDIT_CARD:2222").getId();
        Map<String, Object> body = creditCard("2222", sid);
        body.remove("creditLimit");                      // "Credit limit is required" is thrown after the claim

        api.postJson("/api/v1/accounts", body, 400);

        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(accountCount(userId)).isZero();
        api.create("/api/v1/accounts", creditCard("2222", sid));   // and it can still be used
    }

    @Test
    void theSecondCreateFromTheSameSuggestionIsRejected() throws Exception {
        suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        UUID sid = row(userId, "hdfc:CREDIT_CARD:2222").getId();

        UUID first = api.create("/api/v1/accounts", creditCard("2222", sid));
        JsonNode err = api.postJson("/api/v1/accounts", creditCard("2222", sid), 400);

        assertThat(err.toString()).contains("This suggestion was already handled");
        assertThat(accountCount(userId)).isEqualTo(1);
        assertThat(repository.findById(sid).orElseThrow().getResolvedAccountId()).isEqualTo(first);
    }

    @Test
    void aBankSuggestionNeverLinksADebitCardWhoseNumberEqualsTheAccountNumber() throws Exception {
        suggestions.apply(userId, detection(entity("icici", "1111", Kind.ACCOUNT, Conf.HIGH)));
        UUID sid = row(userId, "icici:BANK_ACCOUNT:1111").getId();

        UUID accountId = api.create("/api/v1/accounts", new HashMap<>(Map.of("type", "bank_account", "name", "Savings",
                "openingBalance", 0, "last4", "1111", "bank", "icici", "financialPosition", "asset",
                "excludeFromNetAsset", false, "debitCard", Map.of("last4", "1111"), "fromSuggestionId", sid)));

        AccountSuggestion s = repository.findById(sid).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.ADDED);
        assertThat(s.getResolvedAccountId()).isEqualTo(accountId);
        assertThat(s.getResolvedCardId()).isNull();
    }

    @Test
    void childrenMatchByRoleAndClosedEarlierCardsNeverCount() throws Exception {
        Detection.Entity debit = entity("icici", "7777", Kind.DEBIT_CARD, Conf.HIGH);
        Detection.Entity earlier = entity("icici", "5555", Kind.DEBIT_CARD, Conf.HIGH);
        suggestions.apply(userId, detection(entity("icici", "1111", Kind.ACCOUNT, Conf.HIGH), debit, earlier));
        UUID bank = row(userId, "icici:BANK_ACCOUNT:1111").getId();
        assertThat(row(userId, "icici:DEBIT_CARD:7777").getParentSuggestionId()).isEqualTo(bank);

        UUID accountId = api.create("/api/v1/accounts", new HashMap<>(Map.of("type", "bank_account", "name", "Savings",
                "openingBalance", 0, "last4", "1111", "bank", "icici", "financialPosition", "asset",
                "excludeFromNetAsset", false, "debitCard", Map.of("last4", "7777"),
                "earlierCards", List.of(Map.of("last4", "5555")), "fromSuggestionId", bank)));

        AccountSuggestion open = row(userId, "icici:DEBIT_CARD:7777");
        assertThat(open.getState()).isEqualTo(State.ADDED);
        assertThat(open.getResolvedCardId()).isEqualTo(cardId(accountId, "7777"));
        assertThat(row(userId, "icici:DEBIT_CARD:5555").getState()).isEqualTo(State.NEW);
    }

    @Test
    void aSettleReallyCommitsParkedMailAfterACreateFromASuggestion() throws Exception {
        UUID existing = api.create("/api/v1/accounts", new HashMap<>(Map.of("type", "bank_account", "name", "Old savings",
                "openingBalance", 0, "last4", "0438", "financialPosition", "asset", "excludeFromNetAsset", false)));
        GmailConnection conn = new GmailConnection();
        conn.setUser(userRepository.findById(userId).orElseThrow());
        conn.setEmail("parked-" + UUID.randomUUID() + "@example.test");
        conn.setEncryptedRefreshToken("refresh-token");
        conn = connectionRepository.save(conn);
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(conn);
        m.setUser(conn.getUser());
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4("0438");
        UUID messageId = messageRepository.save(m).getId();
        try {
            suggestions.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
            api.create("/api/v1/accounts", creditCard("2222", row(userId, "hdfc:CREDIT_CARD:2222").getId()));

            String status = jdbc.queryForObject("select status from gmail_processed_messages where id = ?", String.class, messageId);
            assertThat(status).isNotEqualTo("UNRESOLVED_ACCOUNT");
            assertThat(messageRepository.findById(messageId).orElseThrow().getAccount().getId()).isEqualTo(existing);
        } finally {
            messageRepository.deleteById(messageId);
            connectionRepository.delete(conn);
        }
    }

    @Test
    void primaryAndAddCardFlowsTakeAFromSuggestionId() throws Exception {
        UUID bank = api.create("/api/v1/accounts", new HashMap<>(Map.of("type", "bank_account", "name", "Savings",
                "openingBalance", 0, "last4", "1111", "bank", "icici", "financialPosition", "asset", "excludeFromNetAsset", false)));
        UUID card = api.create("/api/v1/accounts", creditCard("2222", null));
        suggestions.apply(userId, detection(entity("icici", "7777", Kind.DEBIT_CARD, Conf.HIGH), addonOf("8888", "hdfc:2222")));
        AccountSuggestion debit = row(userId, "icici:DEBIT_CARD:7777");
        assertThat(debit.getParentAccountId()).isEqualTo(bank);

        api.postJson("/api/v1/accounts/" + bank + "/cardholders/primary", Map.of("last4", "7777", "fromSuggestionId", debit.getId()), 201);

        AccountSuggestion afterPrimary = repository.findById(debit.getId()).orElseThrow();
        assertThat(afterPrimary.getState()).isEqualTo(State.ADDED);
        assertThat(afterPrimary.getResolvedCardId()).isEqualTo(cardId(bank, "7777"));

        JsonNode holder = api.postJson("/api/v1/accounts/" + card + "/cardholders", Map.of("relationship", "SPOUSE"), 201);
        AccountSuggestion addon = row(userId, "hdfc:CREDIT_CARD:8888");
        api.postJson("/api/v1/accounts/" + card + "/cardholders/" + holder.get("id").asText() + "/cards",
                Map.of("last4", "8888", "fromSuggestionId", addon.getId()), 201);

        AccountSuggestion afterCard = repository.findById(addon.getId()).orElseThrow();
        assertThat(afterCard.getState()).isEqualTo(State.ADDED);
        assertThat(afterCard.getResolvedCardId()).isEqualTo(cardId(card, "8888"));
    }

    @Test
    void aCardNumberThatDoesNotMatchTheSuggestionIsRejected() throws Exception {
        UUID card = api.create("/api/v1/accounts", creditCard("2222", null));
        suggestions.apply(userId, detection(addonOf("8888", "hdfc:2222")));
        UUID sid = row(userId, "hdfc:CREDIT_CARD:8888").getId();

        JsonNode err = api.postJson("/api/v1/accounts/" + card + "/cardholders",
                Map.of("relationship", "SPOUSE", "last4", "6666", "fromSuggestionId", sid), 400);
        assertThat(err.toString()).contains("The card number doesn't match the suggestion");
        api.postJson("/api/v1/accounts/" + card + "/cardholders",
                Map.of("relationship", "SPOUSE", "fromSuggestionId", sid), 400);   // no number at all

        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(api.getJson("/api/v1/accounts/" + card + "/cardholders", 200)).hasSize(1);
    }
}
