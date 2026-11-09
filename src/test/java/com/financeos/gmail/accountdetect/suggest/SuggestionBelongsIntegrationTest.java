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

/** "Belongs to" and "not part" over real persistence and the API. Made-up data. */
@SpringBootTest
@AutoConfigureMockMvc
class SuggestionBelongsIntegrationTest {

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

    /** Every run reports everything found so far (a run that stops reporting a NEW row retires it). */
    private final java.util.List<Detection.Entity> reported = new java.util.ArrayList<>();

    private UUID suggest(Detection.Entity e, String key) {
        reported.add(e);
        service.apply(userId, detection(reported.toArray(new Detection.Entity[0])));
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
    void olderNumberIsSavedClosedOnThePrimaryHolderAndTheActiveCardIsUntouched() throws Exception {
        UUID account = creditCard(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "2222", Kind.CREDIT_CARD);

        belongs(sid, account, "OLDER_NUMBER", 204);

        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).get("last4")).isEqualTo("1111");
        assertThat(cards.get(0).get("closed_on")).isNull();
        assertThat(cards.get(1).get("last4")).isEqualTo("2222");
        assertThat(cards.get(1).get("closed_on").toString()).startsWith("2026-10-20");   // the suggestion's last-seen date
        assertThat(cards.get(1).get("holder")).isEqualTo(cards.get(0).get("holder"));
        AccountSuggestion s = repository.findById(sid).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.BELONGS);
        assertThat(s.getResolvedAccountId()).isEqualTo(account);
        assertThat(s.getResolvedCardId()).isNotNull();
        verify(settler).settleAllInNewTransaction(userId);
        assertThat(api.getJson(BASE, 200).get("items")).isEmpty();
    }

    @Test
    void olderNumberWithoutALastSeenDateIsClosedToday() throws Exception {
        UUID account = creditCard(api, "hdfc", "1111");
        Detection.Entity e = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        e.last = null;
        UUID sid = suggest(e, "hdfc:CREDIT_CARD:2222");

        belongs(sid, account, "OLDER_NUMBER", 204);

        assertThat(cards(account).get(1).get("closed_on").toString()).startsWith(AppTime.today().toString());
    }

    @Test
    void addonCreatesANewHolderWithTheCardOpen() throws Exception {
        UUID account = creditCard(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "8888", Kind.CREDIT_CARD);

        belongs(sid, account, "ADDON", 204, "relationship", "SPOUSE", "holderName", "Asha Test");

        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(2);
        Map<String, Object> addon = cards.get(1);
        assertThat(addon.get("last4")).isEqualTo("8888");
        assertThat(addon.get("closed_on")).isNull();
        assertThat(addon.get("role")).isEqualTo("ADDON");
        assertThat(addon.get("rel")).isEqualTo("SPOUSE");
        assertThat(addon.get("name")).isEqualTo("Asha Test");
        assertThat(cards.get(0).get("closed_on")).isNull();
        AccountSuggestion s = repository.findById(sid).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.BELONGS);
        assertThat(s.getResolvedCardId()).isNotNull();
    }

    @Test
    void addonNeedsARelationshipAndLeavesTheSuggestionNew() throws Exception {
        UUID account = creditCard(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "8888", Kind.CREDIT_CARD);

        belongs(sid, account, "ADDON", 400);

        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(cards(account)).hasSize(1);
        verify(settler, never()).settleAllInNewTransaction(any());
    }

    @Test
    void openCardIsRefusedWhileThePrimaryHolderHasAnActiveCard() throws Exception {
        UUID account = creditCard(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "2222", Kind.CREDIT_CARD);

        JsonNode err = belongs(sid, account, "OPEN_CARD", 400);

        assertThat(err.toString()).contains("already has an active card");
        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("closed_on")).isNull();
    }

    @Test
    void openCardIsAddedWhenThePrimaryHolderHasNoActiveCard() throws Exception {
        UUID account = creditCard(api, "hdfc", "1111");
        jdbc.update("update cards set closed_on = ? where account_id = ?", java.sql.Date.valueOf(LocalDate.of(2026, 5, 1)), account.toString());
        UUID sid = suggest("hdfc", "2222", Kind.CREDIT_CARD);

        belongs(sid, account, "OPEN_CARD", 204);

        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(2);
        assertThat(cards.get(1).get("last4")).isEqualTo("2222");
        assertThat(cards.get(1).get("closed_on")).isNull();
        assertThat(cards.get(1).get("holder")).isEqualTo(cards.get(0).get("holder"));
        assertThat(repository.findById(sid).orElseThrow().getResolvedCardId()).isNotNull();
    }

    @Test
    void debitCardOnABankAccountCreatesTheHolderAndTheCardThenRefusesASecondActiveOne() throws Exception {
        UUID account = bankAccount(api, "hdfc", "5555");
        UUID first = suggest("hdfc", "6666", Kind.DEBIT_CARD);

        belongs(first, account, "DEBIT_CARD", 204);

        List<Map<String, Object>> cards = cards(account);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("last4")).isEqualTo("6666");
        assertThat(cards.get(0).get("closed_on")).isNull();
        assertThat(cards.get(0).get("role")).isEqualTo("PRIMARY");
        AccountSuggestion s = repository.findById(first).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.BELONGS);
        assertThat(s.getResolvedAccountId()).isEqualTo(account);
        assertThat(s.getResolvedCardId()).isNotNull();

        UUID second = suggest("hdfc", "7777", Kind.DEBIT_CARD);
        belongs(second, account, "DEBIT_CARD", 400);
        assertThat(repository.findById(second).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(cards(account)).hasSize(1);
    }

    @Test
    void aliasSavesTheNumberAsAnAccountNumberAliasOnTheBankAccount() throws Exception {
        UUID account = bankAccount(api, "hdfc", "5555");
        UUID sid = suggest("hdfc", "9999", Kind.ACCOUNT);

        belongs(sid, account, "ALIAS", 204);

        assertThat(jdbc.queryForObject("select count(*) from account_identifiers where account_id = ? and value = '9999' and kind = 'ACCOUNT_NUMBER'",
                Long.class, account.toString())).isEqualTo(1);
        AccountSuggestion s = repository.findById(sid).orElseThrow();
        assertThat(s.getState()).isEqualTo(State.BELONGS);
        assertThat(s.getResolvedAccountId()).isEqualTo(account);
        assertThat(s.getResolvedCardId()).isNull();
        verify(settler).settleAllInNewTransaction(userId);
    }

    @Test
    void aFailureAfterTheClaimLeavesTheSuggestionNewAndNothingSaved() throws Exception {
        UUID account = bankAccount(api, "hdfc", "5555");
        UUID other = bankAccount(api, "hdfc", "4444");
        belongs(suggest("hdfc", "9999", Kind.ACCOUNT), other, "ALIAS", 204);
        // a second row with the same number: the alias is already on another account, so this fails after the claim
        UUID second = suggest("hdfc", "9999", Kind.DEBIT_CARD);

        belongs(second, account, "ALIAS", 400);

        assertThat(repository.findById(second).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(repository.findById(second).orElseThrow().getResolvedAccountId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from account_identifiers where account_id = ?", Long.class, account.toString())).isZero();
    }

    @Test
    void wrongBankWrongKindAndAnotherUsersAccountAreRejected() throws Exception {
        UUID wrongBank = creditCard(api, "icici", "1111");
        UUID card = creditCard(api, "hdfc", "1111");
        UUID bank = bankAccount(api, "hdfc", "5555");
        UUID theirs = creditCard(otherApi, "hdfc", "1111");
        UUID sid = suggest("hdfc", "2222", Kind.CREDIT_CARD);

        assertThat(belongs(sid, wrongBank, "OLDER_NUMBER", 400).toString()).contains("Only accounts at ").contains(" can hold this number");
        belongs(sid, bank, "OLDER_NUMBER", 400);   // card kind on a bank account
        belongs(sid, bank, "ADDON", 400, "relationship", "SPOUSE");
        belongs(sid, card, "DEBIT_CARD", 400);     // bank kind on a card account
        belongs(sid, card, "ALIAS", 400);
        belongs(sid, theirs, "OLDER_NUMBER", 400);
        belongs(sid, UUID.randomUUID(), "OLDER_NUMBER", 400);

        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(cards(card)).hasSize(1);
        assertThat(cards(theirs)).hasSize(1);
    }

    @Test
    void aSuggestionsKindMustFitTheAnswer() throws Exception {
        UUID card = creditCard(api, "hdfc", "1111");
        UUID bank = bankAccount(api, "hdfc", "5555");
        UUID cardSid = suggest("hdfc", "2222", Kind.CREDIT_CARD);
        UUID bankSid = suggest("hdfc", "3333", Kind.ACCOUNT);

        assertThat(belongs(cardSid, bank, "ALIAS", 400).toString()).contains("credit card account");
        assertThat(belongs(bankSid, card, "OLDER_NUMBER", 400).toString()).contains("bank account number");
        belongs(bankSid, card, "ADDON", 400, "relationship", "SPOUSE");

        assertThat(repository.findById(cardSid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(repository.findById(bankSid).orElseThrow().getState()).isEqualTo(State.NEW);
        assertThat(cards(card)).hasSize(1);
    }

    @Test
    void onlyANewOwnSuggestionCanBeAnswered() throws Exception {
        UUID card = creditCard(api, "hdfc", "1111");
        UUID sid = suggest("hdfc", "2222", Kind.CREDIT_CARD);
        service.notMine(userId, sid);
        belongs(sid, card, "OLDER_NUMBER", 400);

        UUID mine = suggest("hdfc", "3333", Kind.CREDIT_CARD);
        UUID theirsCard = creditCard(otherApi, "hdfc", "1111");
        otherApi.postJson(BASE + "/" + mine + "/belongs", Map.of("accountId", theirsCard, "as", "OLDER_NUMBER"), 400);
        assertThat(repository.findById(mine).orElseThrow().getState()).isEqualTo(State.NEW);
        api.postJson(BASE + "/" + UUID.randomUUID() + "/belongs", Map.of("accountId", card, "as", "OLDER_NUMBER"), 400);
    }

    // ---------------------------------------------------------------- the matcher asks first

    private Detection replacedDetection(Conf lifecycleConf) {
        Detection.Entity old = entity("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        old.lifecycle = Lifecycle.REPLACED;
        old.lifecycleConf = lifecycleConf;
        old.linkedTo = "hdfc:2222";
        return detection(old);
    }

    @Test
    void anOldNumberOfAnExistingCardIsLinkedAsOlderAndNotPartIsFinal() throws Exception {
        UUID account = creditCard(api, "hdfc", "2222");
        service.apply(userId, replacedDetection(Conf.HIGH));
        AccountSuggestion s = row("hdfc:CREDIT_CARD:1111");
        assertThat(s.getParentAccountId()).isEqualTo(account);
        assertThat(s.getBand()).isEqualTo(AccountSuggestion.Band.OLDER);
        JsonNode item = api.getJson(BASE, 200).get("items").get(0);
        assertThat(item.get("parentAccount").get("id").asText()).isEqualTo(account.toString());

        api.postJson(BASE + "/" + s.getId() + "/not-part", null, 204);

        AccountSuggestion after = row("hdfc:CREDIT_CARD:1111");
        assertThat(after.getParentAccountId()).isNull();
        assertThat(after.isParentDeclined()).isTrue();
        assertThat(after.getState()).isEqualTo(State.NEW);
        service.apply(userId, replacedDetection(Conf.HIGH));   // a re-scan
        assertThat(row("hdfc:CREDIT_CARD:1111").getParentAccountId()).isNull();
        assertThat(cards(account)).hasSize(1);
        api.postJson(BASE + "/" + s.getId() + "/not-part", null, 400);   // nothing left to refuse
    }

    @Test
    void aLowLifecycleDoesNotLinkAndNotPartNeedsALink() throws Exception {
        creditCard(api, "hdfc", "2222");
        service.apply(userId, replacedDetection(Conf.LOW));
        AccountSuggestion s = row("hdfc:CREDIT_CARD:1111");
        assertThat(s.getParentAccountId()).isNull();
        api.postJson(BASE + "/" + s.getId() + "/not-part", null, 400);
        otherApi.postJson(BASE + "/" + s.getId() + "/not-part", null, 404);
    }

    @Test
    void yesOnTheOlderNumberQuestionSavesItClosedOnTheLinkedAccount() throws Exception {
        UUID account = creditCard(api, "hdfc", "2222");
        service.apply(userId, replacedDetection(Conf.HIGH));
        UUID sid = row("hdfc:CREDIT_CARD:1111").getId();

        belongs(sid, account, "OLDER_NUMBER", 204);

        assertThat(cards(account)).hasSize(2);
        assertThat(repository.findById(sid).orElseThrow().getState()).isEqualTo(State.BELONGS);
    }
}
