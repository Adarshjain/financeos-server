package com.financeos.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.StatementPasswordSavedEvent;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Detector;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionRepository;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionService;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating an account from a suggestion whose statements opened by a profile rule saves the statement password built
 * server-side from the profile; it is never returned. Made-up profile: Riya Sharma, born 14 Aug 1994.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class CreateFromSuggestionPasswordIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountSuggestionService suggestions;
    @Autowired private AccountSuggestionRepository repository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationEvents events;

    private ApiTestClient api;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "from-sugg-pwd-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        userId = user.getId();
        user.setDisplayName("Riya Sharma");
        user.setBirthDate("1994-08-14");
        userRepository.save(user);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private UUID suggestion(String bank, String last4, Kind kind, String rule) {
        Detection d = detection(entity(bank, last4, kind, Conf.HIGH));
        StatementContent s = new StatementContent("PDF_MAIL", bank, last4, kind, YearMonth.of(2026, 9), null, null, List.of(), false,
                LocalDate.of(2026, 9, 15), "cluster1", LocalDate.of(2026, 9, 16), null, null, rule);
        d.statementLinks.add(new Detection.StatementLink(s.source(), Detector.LINK_LAST4, bank, last4, s.period(), s));
        suggestions.apply(userId, d);
        String type = kind == Kind.ACCOUNT ? "BANK_ACCOUNT" : "CREDIT_CARD";
        return repository.findByUserId(userId).stream().filter(x -> x.getKey().equals(bank + ":" + type + ":" + last4)).findFirst().orElseThrow().getId();
    }

    private Map<String, Object> card(String last4, UUID suggestionId, String password) {
        Map<String, Object> body = new HashMap<>(Map.of("type", "credit_card", "name", "Suggested Card", "last4", last4,
                "creditLimit", 100000, "anniversaryDate", "2026-01-15", "financialPosition", "liability", "excludeFromNetAsset", false, "bank", "hdfc"));
        if (suggestionId != null) body.put("fromSuggestionId", suggestionId);
        if (password != null) body.put("statementPassword", password);
        return body;
    }

    private String savedPassword(UUID accountId) {
        return new TransactionTemplate(transactions).execute(s -> {
            Account a = accountRepository.findById(accountId).orElseThrow();
            return a.getCreditCardDetails() != null ? a.getCreditCardDetails().getStatementPassword()
                    : a.getBankDetails() != null ? a.getBankDetails().getStatementPassword() : null;
        });
    }

    @Test
    void aBlankPasswordIsBuiltFromTheProfileAndTheSuggestionsNumberAndNeverReturned() throws Exception {
        UUID sid = suggestion("hdfc", "2222", Kind.CREDIT_CARD, "NAME4U+CARD4");

        JsonNode response = api.postJson("/api/v1/accounts", card("2222", sid, null), 201);

        UUID accountId = UUID.fromString(response.get("id").asText());
        assertThat(savedPassword(accountId)).isEqualTo("RIYA2222");
        assertThat(response.toString()).doesNotContain("RIYA2222");
        assertThat(api.getJson("/api/v1/accounts/" + accountId, 200).toString()).doesNotContain("RIYA2222");
        assertThat(api.getJson("/api/v1/accounts", 200).toString()).doesNotContain("RIYA2222");
    }

    @Test
    void aBankAccountGetsItsDatePasswordToo() throws Exception {
        UUID sid = suggestion("icici", "1111", Kind.ACCOUNT, "NAME4L+DDMM");

        UUID accountId = api.create("/api/v1/accounts", new HashMap<>(Map.of("type", "bank_account", "name", "Savings", "openingBalance", 0,
                "last4", "1111", "bank", "icici", "financialPosition", "asset", "excludeFromNetAsset", false, "fromSuggestionId", sid)));

        assertThat(savedPassword(accountId)).isEqualTo("riya1408");
    }

    @Test
    void aPasswordTheUserTypedIsKept() throws Exception {
        UUID sid = suggestion("hdfc", "2222", Kind.CREDIT_CARD, "NAME4U+CARD4");

        UUID accountId = api.create("/api/v1/accounts", card("2222", sid, "typed-by-user"));

        assertThat(savedPassword(accountId)).isEqualTo("typed-by-user");
    }

    @Test
    void aSuggestionWithoutARuleLeavesThePasswordEmpty() throws Exception {
        Detection d = detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH));
        suggestions.apply(userId, d);
        UUID sid = repository.findByUserId(userId).get(0).getId();
        assertThat(repository.findById(sid).orElseThrow().getPasswordRule()).isNull();

        UUID accountId = api.create("/api/v1/accounts", card("2222", sid, null));

        assertThat(savedPassword(accountId)).isNull();
    }

    @Test
    void aMissingNameOrBirthDateLeavesThePasswordEmpty() throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        user.setBirthDate(null);
        userRepository.save(user);
        UUID sid = suggestion("icici", "1111", Kind.ACCOUNT, "NAME4L+DDMM");

        UUID accountId = api.create("/api/v1/accounts", new HashMap<>(Map.of("type", "bank_account", "name", "Savings", "openingBalance", 0,
                "last4", "1111", "bank", "icici", "financialPosition", "asset", "excludeFromNetAsset", false, "fromSuggestionId", sid)));

        assertThat(savedPassword(accountId)).isNull();
    }

    @Test
    void anAccountSavedWithAPasswordPublishesTheRetryEventAndAPlainOneDoesNot() throws Exception {
        api.create("/api/v1/accounts", card("4444", null, null));
        assertThat(events.stream(StatementPasswordSavedEvent.class).count()).isZero();

        UUID withPwd = api.create("/api/v1/accounts", card("3333", null, "typed-by-user"));
        assertThat(events.stream(StatementPasswordSavedEvent.class).map(StatementPasswordSavedEvent::userId)).containsExactly(userId);

        // an update that keeps the same password publishes nothing; one that changes it does; a password built from a suggestion counts too
        api.putJson("/api/v1/accounts/" + withPwd, card("3333", null, "typed-by-user"), 200);
        assertThat(events.stream(StatementPasswordSavedEvent.class).count()).isEqualTo(1);
        api.putJson("/api/v1/accounts/" + withPwd, card("3333", null, "changed-by-user"), 200);
        assertThat(events.stream(StatementPasswordSavedEvent.class).count()).isEqualTo(2);
        UUID sid = suggestion("hdfc", "2222", Kind.CREDIT_CARD, "NAME4U+CARD4");
        api.create("/api/v1/accounts", card("2222", sid, null));
        assertThat(events.stream(StatementPasswordSavedEvent.class).count()).isEqualTo(3);
    }
}
