package com.financeos.gmail.accountdetect.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

/** Suggestions over real persistence: re-runs, matching, notification count, tenancy and the API. Made-up data. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountSuggestionIntegrationTest {

    private static final String BASE = "/api/v1/account-suggestions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountSuggestionService service;
    @Autowired private AccountSuggestionRepository repository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "sugg-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "sugg-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private UUID bankAccount(ApiTestClient client, String bank, String last4) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "bank_account", "name", "Savings " + bank, "openingBalance", 0,
                "financialPosition", "asset", "excludeFromNetAsset", false));
        if (bank != null) body.put("bank", bank);
        if (last4 != null) body.put("last4", last4);
        return client.create("/api/v1/accounts", body);
    }

    private UUID creditCard(ApiTestClient client, String bank, String last4) throws Exception {
        return client.create("/api/v1/accounts", new HashMap<>(Map.of("type", "credit_card", "name", "Card " + bank,
                "last4", last4, "creditLimit", 100000, "anniversaryDate", "2026-01-15", "financialPosition", "liability",
                "excludeFromNetAsset", false, "bank", bank)));
    }

    private AccountSuggestion row(String key) {
        return repository.findByUserId(userId).stream().filter(s -> s.getKey().equals(key)).findFirst().orElseThrow();
    }

    private static Detection.Entity e(String bank, String last4, Kind kind, Conf exists) {
        return entity(bank, last4, kind, exists);
    }

    // ---------------------------------------------------------------- re-runs

    @Test
    void reRunsNeverChangeAnsweredRowsAndUpdateNewOnes() {
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH),
                e("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH), e("axis", "4444", Kind.CREDIT_CARD, Conf.HIGH)));
        service.notMine(userId, row("hdfc:BANK_ACCOUNT:1111").getId());
        AccountSuggestion added = row("hdfc:CREDIT_CARD:2222");
        added.setState(State.ADDED);
        repository.save(added);
        AccountSuggestion belongs = row("icici:CREDIT_CARD:3333");
        belongs.setState(State.BELONGS);
        repository.save(belongs);

        Detection.Entity changed = e("axis", "4444", Kind.CREDIT_CARD, Conf.HIGH);
        changed.variant = "Magnus";
        changed.variantConf = Conf.MEDIUM;
        Detection.Entity changedAnswered = e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        changedAnswered.variant = "Regalia";
        int marked = service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), changedAnswered,
                e("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH), changed));

        assertThat(marked).isZero();
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.NOT_MINE);
        assertThat(row("hdfc:CREDIT_CARD:2222").getState()).isEqualTo(State.ADDED);
        assertThat(row("hdfc:CREDIT_CARD:2222").getVariant()).isNull();
        assertThat(row("icici:CREDIT_CARD:3333").getState()).isEqualTo(State.BELONGS);
        assertThat(row("axis:CREDIT_CARD:4444").getState()).isEqualTo(State.NEW);
        assertThat(row("axis:CREDIT_CARD:4444").getVariant()).isEqualTo("Magnus");

        // a row no longer reported stays as it is
        service.apply(userId, detection());
        assertThat(repository.findByUserId(userId)).hasSize(4);
        assertThat(row("axis:CREDIT_CARD:4444").getState()).isEqualTo(State.NEW);
    }

    @Test
    void aNewRowBecomesMatchedOnceTheUserCreatesTheAccountByHand() throws Exception {
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        assertThat(api.getJson(BASE, 200).get("items")).hasSize(1);

        UUID created = bankAccount(api, "hdfc", "1111");
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));

        AccountSuggestion r = row("hdfc:BANK_ACCOUNT:1111");
        assertThat(r.getState()).isEqualTo(State.MATCHED);
        assertThat(r.getResolvedAccountId()).isEqualTo(created);
        assertThat(api.getJson(BASE, 200).get("items")).isEmpty();
    }

    @Test
    void existingNumbersStartMatchedIncludingClosedCards() throws Exception {
        UUID card = creditCard(api, "hdfc", "2222");
        jdbc.update("UPDATE cards SET closed_on = ? WHERE account_id = ?", LocalDate.of(2025, 1, 1), card.toString());
        service.apply(userId, detection(e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        assertThat(row("hdfc:CREDIT_CARD:2222").getState()).isEqualTo(State.MATCHED);
        assertThat(row("hdfc:CREDIT_CARD:2222").getResolvedCardId()).isNotNull();
    }

    // ---------------------------------------------------------------- notification count

    @Test
    void foundAndOlderAreCountedOnceMaybeNever() {
        Detection.Entity older = e("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH);
        older.last = LocalDate.of(2023, 1, 1);
        Detection.Entity maybe = e("axis", "4444", Kind.CREDIT_CARD, Conf.LOW);
        int first = service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), older, maybe));
        assertThat(first).isEqualTo(2);
        assertThat(row("axis:CREDIT_CARD:4444").getNotifiedAt()).isNull();

        int again = service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), older, maybe));
        assertThat(again).isZero();

        // the MAYBE firms up: now it is worth telling, once
        int firmed = service.apply(userId, detection(e("axis", "4444", Kind.CREDIT_CARD, Conf.MEDIUM)));
        assertThat(firmed).isEqualTo(1);
        assertThat(service.apply(userId, detection(e("axis", "4444", Kind.CREDIT_CARD, Conf.MEDIUM)))).isZero();
    }

    @Test
    void matchedRowsAreNeverCounted() throws Exception {
        bankAccount(api, "hdfc", "1111");
        assertThat(service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)))).isZero();
    }

    // ---------------------------------------------------------------- tenancy

    @Test
    void anotherUsersAccountsNeverHideMySuggestions() throws Exception {
        bankAccount(otherApi, "hdfc", "1111");
        creditCard(otherApi, "hdfc", "2222");
        bankAccount(otherApi, "hdfc", null);   // never my "Is this your…" candidate either

        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));

        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.NEW);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getCandidateAccountId()).isNull();
        assertThat(row("hdfc:CREDIT_CARD:2222").getState()).isEqualTo(State.NEW);
        assertThat(otherApi.getJson(BASE, 200).get("items")).isEmpty();
        UUID mine = row("hdfc:CREDIT_CARD:2222").getId();
        otherApi.postJson(BASE + "/" + mine + "/not-mine", Map.of(), 404);
        assertThat(row("hdfc:CREDIT_CARD:2222").getState()).isEqualTo(State.NEW);
    }

    // ---------------------------------------------------------------- parents among suggestions

    @Test
    void addonAndDebitCardsNestUnderTheirParentSuggestions() throws Exception {
        Detection.Entity primary = e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        primary.variant = "Regalia Gold";
        primary.variantConf = Conf.LOW;
        primary.variantCandidates.addAll(List.of("Regalia Gold", "Regalia"));
        primary.first = LocalDate.of(2022, 3, 5);
        primary.evidenceMails = 64;
        Detection.Entity addon = e("hdfc", "8888", Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.MEDIUM;
        addon.linkedTo = "hdfc:2222";
        service.apply(userId, detection(addon, primary, e("icici", "1111", Kind.ACCOUNT, Conf.HIGH), e("icici", "7777", Kind.DEBIT_CARD, Conf.HIGH)));

        assertThat(row("hdfc:CREDIT_CARD:8888").getParentSuggestionId()).isEqualTo(row("hdfc:CREDIT_CARD:2222").getId());
        assertThat(row("icici:DEBIT_CARD:7777").getParentSuggestionId()).isEqualTo(row("icici:BANK_ACCOUNT:1111").getId());

        JsonNode body = api.getJson(BASE, 200);
        assertThat(body.get("scan").get("status").asText()).isEqualTo("NOT_STARTED");
        assertThat(body.get("scan").get("gmailConnected").asBoolean()).isFalse();
        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        JsonNode card = null;
        JsonNode bank = null;
        for (JsonNode i : items) {
            if (i.get("last4").asText().equals("2222")) card = i;
            if (i.get("last4").asText().equals("1111")) bank = i;
        }
        assertThat(card).isNotNull();
        assertThat(bank).isNotNull();
        assertThat(card.get("kind").asText()).isEqualTo("CREDIT_CARD");
        assertThat(card.get("band").asText()).isEqualTo("FOUND");
        assertThat(card.get("bank").get("id").asText()).isEqualTo("hdfc");
        assertThat(card.get("bank").get("name").asText()).isEqualTo("HDFC Bank");
        assertThat(card.get("caption").asText()).isEqualTo("Regalia Gold or Regalia · 1 add-on · 64 emails since Mar 2022");
        assertThat(card.get("children")).hasSize(1);
        assertThat(card.get("children").get(0).get("kind").asText()).isEqualTo("ADDON_CARD");
        assertThat(card.get("children").get(0).get("parentSuggestionId").asText()).isEqualTo(card.get("id").asText());
        assertThat(bank.get("children").get(0).get("kind").asText()).isEqualTo("DEBIT_CARD");
        assertThat(bank.get("caption").asText()).isEqualTo("1 debit card · 12 emails since Mar 2024");
    }

    @Test
    void debitCardUnderAnExistingSingleBankAccountShowsThatAccount() throws Exception {
        UUID savings = bankAccount(api, "icici", "1111");
        service.apply(userId, detection(e("icici", "7777", Kind.DEBIT_CARD, Conf.HIGH)));
        JsonNode item = api.getJson(BASE, 200).get("items").get(0);
        assertThat(item.get("parentAccount").get("id").asText()).isEqualTo(savings.toString());
        assertThat(item.get("parentAccount").get("last4").asText()).isEqualTo("1111");
    }

    // ---------------------------------------------------------------- answers

    @Test
    void notMineHidesAndUndoBringsItBack() throws Exception {
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        UUID id = row("hdfc:BANK_ACCOUNT:1111").getId();

        api.postJson(BASE + "/" + id + "/undo", Map.of(), 400);   // only from NOT_MINE
        assertThat(api.post(BASE + "/" + id + "/not-mine", Map.of()).getStatus()).isEqualTo(204);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.NOT_MINE);
        assertThat(api.getJson(BASE, 200).get("items")).isEmpty();

        assertThat(api.post(BASE + "/" + id + "/undo", Map.of()).getStatus()).isEqualTo(204);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.NEW);
        assertThat(api.getJson(BASE, 200).get("items")).hasSize(1);
    }

    @Test
    void isThisYesWritesTheLast4OntoTheBankAccount() throws Exception {
        UUID savings = bankAccount(api, "hdfc", null);
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        JsonNode item = api.getJson(BASE, 200).get("items").get(0);
        assertThat(item.get("candidateAccount").get("id").asText()).isEqualTo(savings.toString());
        UUID id = UUID.fromString(item.get("id").asText());

        api.postJson(BASE + "/" + id + "/is-this", Map.of("accountId", UUID.randomUUID(), "yes", true), 400);
        assertThat(api.post(BASE + "/" + id + "/is-this", Map.of("accountId", savings, "yes", true)).getStatus()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT last4 FROM account_bank_details WHERE account_id = ?", String.class, savings.toString())).isEqualTo("1111");
        AccountSuggestion r = row("hdfc:BANK_ACCOUNT:1111");
        assertThat(r.getState()).isEqualTo(State.ADDED);
        assertThat(r.getResolvedAccountId()).isEqualTo(savings);
        assertThat(api.getJson(BASE, 200).get("items")).isEmpty();
    }

    @Test
    void isThisYesAddsAPrimaryCardToACardAccountWithoutOne() throws Exception {
        UUID cardAccount = creditCard(api, "hdfc", "9999");
        jdbc.update("DELETE FROM cards WHERE account_id = ?", cardAccount.toString());
        jdbc.update("DELETE FROM cardholders WHERE account_id = ?", cardAccount.toString());
        service.apply(userId, detection(e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        AccountSuggestion before = row("hdfc:CREDIT_CARD:2222");
        assertThat(before.getCandidateAccountId()).isEqualTo(cardAccount);

        assertThat(api.post(BASE + "/" + before.getId() + "/is-this", Map.of("accountId", cardAccount, "yes", true)).getStatus()).isEqualTo(204);

        JsonNode holders = api.getJson("/api/v1/accounts/" + cardAccount + "/cardholders", 200);
        assertThat(holders).hasSize(1);
        assertThat(holders.get(0).get("role").asText()).isEqualTo("PRIMARY");
        assertThat(holders.get(0).get("cards").get(0).get("last4").asText()).isEqualTo("2222");
        AccountSuggestion after = row("hdfc:CREDIT_CARD:2222");
        assertThat(after.getState()).isEqualTo(State.ADDED);
        assertThat(after.getResolvedCardId()).isNotNull();
    }

    @Test
    void isThisNoDropsTheCandidateAndKeepsTheSuggestion() throws Exception {
        UUID savings = bankAccount(api, "hdfc", null);
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        UUID id = row("hdfc:BANK_ACCOUNT:1111").getId();

        assertThat(api.post(BASE + "/" + id + "/is-this", Map.of("accountId", savings, "yes", false)).getStatus()).isEqualTo(204);

        AccountSuggestion r = row("hdfc:BANK_ACCOUNT:1111");
        assertThat(r.getState()).isEqualTo(State.NEW);
        assertThat(r.getCandidateAccountId()).isNull();
        assertThat(jdbc.queryForObject("SELECT last4 FROM account_bank_details WHERE account_id = ?", String.class, savings.toString())).isNull();
        // a later run does not ask again
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getCandidateAccountId()).isNull();
    }

    // ---------------------------------------------------------------- review fixes

    @Test
    void unknownBankEntitiesAreSkippedAndOddValuesNeverFailTheApply() {
        Detection.Entity odd = e("?news.example.test", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity longName = e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        longName.variant = "v".repeat(500);
        for (int i = 0; i < 40; i++) longName.variantCandidates.add("n".repeat(100) + i);
        int marked = service.apply(userId, detection(odd, longName, e("axis", "4444", Kind.CREDIT_CARD, Conf.HIGH)));
        assertThat(marked).isEqualTo(2);
        assertThat(repository.findByUserId(userId)).extracting(AccountSuggestion::getKey)
                .containsExactlyInAnyOrder("hdfc:CREDIT_CARD:2222", "axis:CREDIT_CARD:4444");
        assertThat(row("hdfc:CREDIT_CARD:2222").getVariant()).hasSize(150);
    }

    @Test
    void lowConfidenceRowsAreMaybeAndStoreTheKindAndLifecycleConfidence() throws Exception {
        Detection.Entity lowKind = e("hdfc", "1111", Kind.CREDIT_CARD, Conf.HIGH);
        lowKind.kindConf = Conf.LOW;
        Detection.Entity lowExists = e("axis", "2222", Kind.CREDIT_CARD, Conf.LOW);
        lowExists.last = LocalDate.of(2020, 1, 1);   // stale, but LOW is checked first
        Detection.Entity sure = e("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH);
        sure.lifecycle = Lifecycle.STOPPED;
        sure.lifecycleConf = Conf.LOW;
        int marked = service.apply(userId, detection(lowKind, lowExists, sure));

        assertThat(marked).isEqualTo(1);
        assertThat(row("hdfc:CREDIT_CARD:1111").getBand()).isEqualTo(AccountSuggestion.Band.MAYBE);
        assertThat(row("axis:CREDIT_CARD:2222").getBand()).isEqualTo(AccountSuggestion.Band.MAYBE);
        JsonNode items = api.getJson(BASE, 200).get("items");
        for (JsonNode i : items) {
            if (i.get("last4").asText().equals("3333")) {
                assertThat(i.get("band").asText()).isEqualTo("FOUND");
                assertThat(i.get("kindConf").asText()).isEqualTo("HIGH");
                assertThat(i.get("lifecycle").asText()).isEqualTo("STOPPED");
                assertThat(i.get("lifecycleConf").asText()).isEqualTo("LOW");
            }
            if (i.get("last4").asText().equals("1111")) assertThat(i.get("kindConf").asText()).isEqualTo("LOW");
        }
    }

    @Test
    void sameLast4AtAnotherBankOrKindDoesNotHideAndAnAccountWithoutBankIsOnlyAsked() throws Exception {
        bankAccount(api, "icici", "1111");                 // other bank
        creditCard(api, "hdfc", "2222");                   // a card number is not a bank account number
        UUID noBank = bankAccount(api, null, "3333");      // no bank, same number: asked, never hiding
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), e("hdfc", "2222", Kind.ACCOUNT, Conf.HIGH),
                e("hdfc", "3333", Kind.ACCOUNT, Conf.HIGH)));
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.NEW);
        assertThat(row("hdfc:BANK_ACCOUNT:2222").getState()).isEqualTo(State.NEW);
        AccountSuggestion asked = row("hdfc:BANK_ACCOUNT:3333");
        assertThat(asked.getState()).isEqualTo(State.NEW);
        assertThat(asked.getCandidateAccountId()).isEqualTo(noBank);
        assertThat(api.getJson(BASE, 200).get("items")).hasSize(3);

        // "yes" on an account that already carries this very number just marks it
        assertThat(api.post(BASE + "/" + asked.getId() + "/is-this", Map.of("accountId", noBank, "yes", true)).getStatus()).isEqualTo(204);
        assertThat(row("hdfc:BANK_ACCOUNT:3333").getState()).isEqualTo(State.ADDED);
    }

    @Test
    void candidatesAreRecomputedEveryRunAndANoIsFinal() throws Exception {
        UUID first = bankAccount(api, "hdfc", null);
        Detection d = detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH), e("hdfc", "2222", Kind.ACCOUNT, Conf.HIGH));
        service.apply(userId, d);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getCandidateAccountId()).isEqualTo(first);

        UUID second = bankAccount(api, "hdfc", null);      // two now: ambiguous, cleared
        service.apply(userId, d);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getCandidateAccountId()).isNull();
        assertThat(row("hdfc:BANK_ACCOUNT:2222").getCandidateAccountId()).isNull();

        jdbc.update("UPDATE accounts SET closed_on = ? WHERE id = ?", LocalDate.of(2020, 1, 1), first.toString());   // one left again
        service.apply(userId, d);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getCandidateAccountId()).isEqualTo(second);

        // "no" on one row is final for that row only
        UUID id = row("hdfc:BANK_ACCOUNT:1111").getId();
        assertThat(api.post(BASE + "/" + id + "/is-this", Map.of("accountId", second, "yes", false)).getStatus()).isEqualTo(204);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").isThisDeclined()).isTrue();
        service.apply(userId, d);
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getCandidateAccountId()).isNull();
        assertThat(row("hdfc:BANK_ACCOUNT:2222").getCandidateAccountId()).isEqualTo(second);
    }

    @Test
    void listHidesANewRowWhoseAccountWasCreatedAfterTheScan() throws Exception {
        Detection.Entity primary = e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity addon = e("hdfc", "8888", Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.MEDIUM;
        addon.linkedTo = "hdfc:2222";
        service.apply(userId, detection(primary, addon, e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        assertThat(api.getJson(BASE, 200).get("items")).hasSize(2);

        bankAccount(api, "hdfc", "1111");          // no scan since
        JsonNode items = api.getJson(BASE, 200).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("last4").asText()).isEqualTo("2222");
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.NEW);   // only the next scan flips it

        // the parent card now exists: the add-on (a child) is hidden or shown alone, never under a hidden parent
        creditCard(api, "hdfc", "2222");
        JsonNode after = api.getJson(BASE, 200).get("items");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).get("last4").asText()).isEqualTo("8888");
        assertThat(after.get(0).get("kind").asText()).isEqualTo("CREDIT_CARD");
        assertThat(after.get(0).get("parentSuggestionId").isNull()).isTrue();
    }

    @Test
    void aChildOfANotMineParentIsShownAloneWithItsBaseKindAndTheLinkIsCleared() throws Exception {
        Detection.Entity primary = e("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity addon = e("hdfc", "8888", Kind.CREDIT_CARD, Conf.MEDIUM);
        addon.lifecycle = Lifecycle.ADD_ON;
        addon.lifecycleConf = Conf.MEDIUM;
        addon.linkedTo = "hdfc:2222";
        service.apply(userId, detection(primary, addon));
        assertThat(row("hdfc:CREDIT_CARD:8888").getKind()).isEqualTo(AccountSuggestion.Kind.ADDON_CARD);
        service.notMine(userId, row("hdfc:CREDIT_CARD:2222").getId());

        // before the next apply: read time already shows it alone with its base kind
        JsonNode items = api.getJson(BASE, 200).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("kind").asText()).isEqualTo("CREDIT_CARD");
        assertThat(items.get(0).get("parentSuggestionId").isNull()).isTrue();

        // the next apply, even one that does not report the child, drops the stale link
        service.apply(userId, detection(primary));
        assertThat(row("hdfc:CREDIT_CARD:8888").getParentSuggestionId()).isNull();
        assertThat(row("hdfc:CREDIT_CARD:8888").getKind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
        service.apply(userId, detection(primary, addon));
        assertThat(row("hdfc:CREDIT_CARD:8888").getParentSuggestionId()).isNull();
        assertThat(row("hdfc:CREDIT_CARD:8888").getKind()).isEqualTo(AccountSuggestion.Kind.CREDIT_CARD);
    }

    @Test
    void aMatchedRowStaysMatchedAfterTheAccountIsDeleted() throws Exception {
        UUID created = bankAccount(api, "hdfc", "1111");
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.MATCHED);
        jdbc.update("DELETE FROM account_bank_details WHERE account_id = ?", created.toString());
        jdbc.update("UPDATE accounts SET bank = NULL WHERE id = ?", created.toString());
        service.apply(userId, detection(e("hdfc", "1111", Kind.ACCOUNT, Conf.HIGH)));
        assertThat(row("hdfc:BANK_ACCOUNT:1111").getState()).isEqualTo(State.MATCHED);
        assertThat(api.getJson(BASE, 200).get("items")).isEmpty();
    }
}
