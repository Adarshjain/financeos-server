package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.sms.device.SmsLoginLimiter;
import com.financeos.sms.line.SmsSuggestionService;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Summary upsert, suggestion, grouped view and the user's line decisions, through HTTP. All data is invented. */
@SpringBootTest
@AutoConfigureMockMvc
class SmsLinesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountIdentifierRepository identifierRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SmsSuggestionService suggestionService;
    @Autowired private SmsLoginLimiter limiter;

    private ApiTestClient api;
    private User user;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clearAll();
        String email = "sms-lines-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        token = pairDevice(user.getEmail());
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        limiter.clearAll();
    }

    private String pairDevice(String email) throws Exception {
        return SmsTestLogin.token(mockMvc, mapper, email);
    }

    private Map<String, Object> line(String sender, String ending, String family, int msgs) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("sender", sender);
        m.put("ending", ending);
        m.put("family", family);
        m.put("msgCount", msgs);
        m.put("txnCount", msgs - 1);
        m.put("firstMsgAt", "2026-01-05T04:00:00Z");
        m.put("lastMsgAt", "2026-11-01T04:00:00Z");
        return m;
    }

    private JsonNode summary(String tok, List<Map<String, Object>> lines) throws Exception {
        var res = mockMvc.perform(post("/api/v1/sms/device/summary").header("Authorization", "Bearer " + tok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("lines", lines,
                                "skipped", Map.of("personal", 1, "otp", 2, "promo", 3, "unknownSender", 4)))))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return mapper.readTree(res.getContentAsString());
    }

    private UUID account(String type, String name, String bank, boolean closed, String... endings) throws Exception {
        UUID id = api.create("/api/v1/accounts", Map.of("type", type, "name", name,
                "financialPosition", "asset", "excludeFromNetAsset", false));
        Account a = accountRepository.findById(id).orElseThrow();
        a.setBank(bank);
        if (closed) a.setClosedOn(LocalDate.of(2026, 1, 1));
        accountRepository.save(a);
        for (String e : endings) {
            identifierRepository.save(new AccountIdentifier(UUID.randomUUID(), user, a, e, AccountIdentifierKind.ACCOUNT_NUMBER, Instant.now()));
        }
        return id;
    }

    private JsonNode allLines() throws Exception {
        return api.getJson("/api/v1/sms/lines", 200);
    }

    private JsonNode findLine(JsonNode view, String sender) {
        for (String group : List.of("unassigned", "notMine")) {
            for (JsonNode l : view.get(group)) if (l.get("sender").asText().equals(sender)) return l;
        }
        for (JsonNode a : view.get("accounts")) {
            for (JsonNode l : a.get("lines")) if (l.get("sender").asText().equals(sender)) return l;
        }
        return null;
    }

    @Test
    void summaryCreatesOffUnseenLinesNormalisesSenderAndIsIdempotent() throws Exception {
        List<Map<String, Object>> lines = List.of(line("VM-HDFCBK-S", "1234", null, 5), line("AX-JUSPAY-T", null, "apay", 3));
        JsonNode first = summary(token, lines);
        assertThat(first.get("newLines").asInt()).isEqualTo(2);

        JsonNode view = allLines();
        assertThat(view.get("unassigned")).hasSize(2);
        JsonNode hdfc = findLine(view, "HDFCBK");
        assertThat(hdfc.get("state").asText()).isEqualTo("OFF");
        assertThat(hdfc.get("isNew").asBoolean()).isTrue();
        assertThat(hdfc.get("provider").asText()).isEqualTo("HDFC Bank");
        assertThat(hdfc.get("msgCount").asInt()).isEqualTo(5);

        JsonNode again = summary(token, List.of(line("HDFCBK", "1234", null, 9), line("JUSPAY", null, "apay", 3)));
        assertThat(again.get("newLines").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_lines WHERE user_id = ?", Integer.class, user.getId().toString())).isEqualTo(2);
        assertThat(findLine(allLines(), "HDFCBK").get("msgCount").asInt()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT last_summary_at FROM sms_devices WHERE user_id = ?", java.sql.Timestamp.class,
                user.getId().toString())).isNotNull();

        api.postJson("/api/v1/sms/lines/seen", null, 204);
        assertThat(findLine(allLines(), "HDFCBK").get("isNew").asBoolean()).isFalse();
    }

    @Test
    void suggestsByFourDigitEndingAndByThreeDigitSuffix() throws Exception {
        UUID savings = account("bank_account", "Test Savings", "hsbc", false, "770412345");
        UUID card = account("bank_account", "Test Current", "hdfc", false, "5678");
        summary(token, List.of(line("HSBCIN", "345", null, 4), line("HDFCBN", "5678", null, 4)));

        JsonNode view = allLines();
        assertThat(view.get("unassigned")).isEmpty();
        assertThat(findLine(view, "HSBCIN").get("suggestedAccountId").asText()).isEqualTo(savings.toString());
        assertThat(findLine(view, "HSBCIN").get("state").asText()).isEqualTo("OFF");
        assertThat(findLine(view, "HDFCBN").get("suggestedAccountId").asText()).isEqualTo(card.toString());
        assertThat(view.get("accounts")).hasSize(2);
    }

    @Test
    void aTwoDigitEndingIsKeptButNeverSuggestedToAnAccount() throws Exception {
        account("bank_account", "Test Current", "hdfc", false, "559912");
        JsonNode result = summary(token, List.of(line("HDFCBK", "12", null, 4), line("HDFCBK", "1", null, 4)));
        // two digits are a line; one digit is not
        assertThat(result.get("lines").asInt()).isEqualTo(1);
        assertThat(result.get("skippedLines").asInt()).isEqualTo(1);
        JsonNode view = allLines();
        JsonNode l = findLine(view, "HDFCBK");
        assertThat(l.get("ending").asText()).isEqualTo("12");
        assertThat(view.get("unassigned")).hasSize(1);
        assertThat(l.get("suggestedAccountId") == null || l.get("suggestedAccountId").isNull()).isTrue();
    }

    @Test
    void ambiguousEndingGivesNoSuggestionUnlessOnlyOneIsOpen() throws Exception {
        account("bank_account", "Axis One", "axis", false, "4321");
        account("bank_account", "Axis Two", "axis", false, "4321");
        UUID open = account("bank_account", "Eq Open", "equitas", false, "8888");
        account("bank_account", "Eq Old", "equitas", true, "8888");
        summary(token, List.of(line("AXISBK", "4321", null, 2), line("EQUTAS", "8888", null, 2)));

        JsonNode view = allLines();
        assertThat(findLine(view, "AXISBK").get("suggestedAccountId").isNull()).isTrue();
        assertThat(view.get("unassigned")).hasSize(1);
        assertThat(findLine(view, "EQUTAS").get("suggestedAccountId").asText()).isEqualTo(open.toString());
    }

    @Test
    void walletLineMatchesAGenericAccountNameDespiteATypoAndRerunFillsLaterAccounts() throws Exception {
        summary(token, List.of(line("MOBIKW", null, null, 6)));
        assertThat(allLines().get("unassigned")).hasSize(1);

        UUID wallet = account("generic", "Mobikiwk", null, false);
        assertThat(suggestionService.rerunForUser(user.getId())).isEqualTo(1);
        JsonNode view = allLines();
        assertThat(view.get("unassigned")).isEmpty();
        assertThat(findLine(view, "MOBIKW").get("suggestedAccountId").asText()).isEqualTo(wallet.toString());
    }

    @Test
    void twoGenericAccountsWithTheSameNameGiveNoSuggestion() throws Exception {
        account("generic", "PayZapp", null, false);
        account("generic", "pay zapp", null, false);
        summary(token, List.of(line("PAYZAP", null, null, 2)));
        assertThat(allLines().get("unassigned")).hasSize(1);
    }

    @Test
    void lineTransitionsKeepTheAccountAndNeverTurnOnByThemselves() throws Exception {
        UUID wallet = account("generic", "Pluxee Meal", null, false);
        summary(token, List.of(line("PLUXEE", "1111", null, 3)));
        UUID lineId = UUID.fromString(findLine(allLines(), "PLUXEE").get("id").asText());
        String base = "/api/v1/sms/lines/" + lineId;

        JsonNode assigned = api.postJson(base + "/account", Map.of("accountId", wallet), 200);
        assertThat(assigned.get("state").asText()).isEqualTo("OFF");
        assertThat(assigned.get("accountId").asText()).isEqualTo(wallet.toString());

        // no transactions and the first message was on 2026-01-05
        JsonNode on = api.postJson(base + "/on", Map.of("accountId", wallet), 200);
        assertThat(on.get("state").asText()).isEqualTo("ON");
        assertThat(on.get("syncFrom").asText()).isEqualTo("2026-01-05");
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/sms/device/config")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString())
                .contains("PLUXEE").contains("2026-01-05");

        assertThat(api.postJson(base + "/off", null, 200).get("state").asText()).isEqualTo("OFF");

        JsonNode explicit = api.postJson(base + "/on", Map.of("accountId", wallet, "syncFrom", "2026-03-01"), 200);
        assertThat(explicit.get("syncFrom").asText()).isEqualTo("2026-03-01");

        assertThat(api.postJson(base + "/not-mine", null, 200).get("state").asText()).isEqualTo("NOT_MINE");
        assertThat(allLines().get("notMine")).hasSize(1);
        JsonNode undone = api.postJson(base + "/undo", null, 200);
        assertThat(undone.get("state").asText()).isEqualTo("OFF");
        assertThat(undone.get("accountId").asText()).isEqualTo(wallet.toString());
        assertThat(allLines().get("notMine")).isEmpty();
    }

    @Test
    void defaultStartIsTheDayAfterTheAccountsLastTransactionAndGroupedViewCountsBySource() throws Exception {
        UUID wallet = account("generic", "Test Wallet", null, false);
        Account acc = accountRepository.findById(wallet).orElseThrow();
        LocalDate today = com.financeos.core.time.AppTime.today();
        txn(acc, today.minusDays(10), TransactionSource.file_upload);
        txn(acc, today.minusDays(3), TransactionSource.gmail_transaction_alert);
        txn(acc, today.minusDays(2), TransactionSource.manual);
        txn(acc, today.minusDays(120), TransactionSource.manual);
        summary(token, List.of(line("HPPAYS", null, null, 7)));
        UUID lineId = UUID.fromString(findLine(allLines(), "HPPAYS").get("id").asText());

        JsonNode on = api.postJson("/api/v1/sms/lines/" + lineId + "/on", Map.of("accountId", wallet), 200);
        assertThat(on.get("syncFrom").asText()).isEqualTo(today.minusDays(2).plusDays(1).toString());

        JsonNode group = allLines().get("accounts").get(0);
        assertThat(group.get("lastTransactionDate").asText()).isEqualTo(today.minusDays(2).toString());
        assertThat(group.get("last60Days").get("statement").asInt()).isEqualTo(1);
        assertThat(group.get("last60Days").get("emailAlert").asInt()).isEqualTo(1);
        assertThat(group.get("last60Days").get("manual").asInt()).isEqualTo(1);
        assertThat(group.get("lines")).hasSize(1);
    }

    @Test
    void anotherUsersLinesAndAccountsAreNotFound() throws Exception {
        UUID mine = account("generic", "Mine", null, false);
        summary(token, List.of(line("CREDWL", null, null, 2)));
        UUID lineId = UUID.fromString(findLine(allLines(), "CREDWL").get("id").asText());

        String otherEmail = "sms-lines-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        UUID otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        try {
            UUID theirs = UUID.fromString(other.create("/api/v1/accounts", Map.of("type", "generic", "name", "Theirs",
                    "financialPosition", "asset", "excludeFromNetAsset", false)).toString());
            String path = "/api/v1/sms/lines/" + lineId;
            other.postJson(path + "/on", Map.of("accountId", theirs), 404);
            other.postJson(path + "/off", null, 404);
            other.postJson(path + "/not-mine", null, 404);
            other.postJson(path + "/undo", null, 404);
            other.postJson(path + "/account", Map.of("accountId", theirs), 404);
            assertThat(other.getJson("/api/v1/sms/lines", 200).get("unassigned")).isEmpty();
            // own line, someone else's account
            api.postJson(path + "/on", Map.of("accountId", theirs), 404);
            api.postJson(path + "/account", Map.of("accountId", theirs), 404);
            api.postJson(path + "/on", Map.of("accountId", mine), 200);
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherId));
        }
    }

    @Test
    void badSendersAreSkippedAndTheRestOfTheBatchIsStored() throws Exception {
        JsonNode result = summary(token, List.of(
                line("VM-HDFCBK-S", "1234", null, 3),
                line("a-very-long-sender-name-over-twenty", null, null, 2),
                line("someone@example.test", null, null, 2),
                line("AB", null, null, 2),
                line("CREDWL", null, null, 2)));
        assertThat(result.get("lines").asInt()).isEqualTo(2);
        assertThat(result.get("skippedLines").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_lines WHERE user_id = ?", Integer.class, user.getId().toString())).isEqualTo(2);
    }

    @Test
    void deletedAccountLeavesTheLineOffAndOutOfTheDeviceConfig() throws Exception {
        UUID wallet = account("generic", "Short Lived", null, false);
        summary(token, List.of(line("HPPAYS", null, null, 4)));
        UUID lineId = UUID.fromString(findLine(allLines(), "HPPAYS").get("id").asText());
        api.postJson("/api/v1/sms/lines/" + lineId + "/on", Map.of("accountId", wallet), 200);
        // what the database does when the account goes (fk ON DELETE SET NULL)
        jdbc.update("UPDATE sms_lines SET account_id = NULL, suggested_account_id = NULL WHERE id = ?", lineId.toString());

        String config = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/sms/device/config")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(config).get("lines")).isEmpty();
        JsonNode line = findLine(allLines(), "HPPAYS");
        assertThat(line.get("state").asText()).isEqualTo("OFF");
        assertThat(line.get("syncFrom").isNull()).isTrue();
    }

    @Test
    void accountCreatedAfterTheSummaryIsSuggestedWhenTheLinesViewLoads() throws Exception {
        summary(token, List.of(line("MOBIKW", null, null, 6)));
        assertThat(allLines().get("unassigned")).hasSize(1);
        UUID wallet = account("generic", "MobiKwik", null, false);
        JsonNode view = allLines();
        assertThat(view.get("unassigned")).isEmpty();
        assertThat(findLine(view, "MOBIKW").get("suggestedAccountId").asText()).isEqualTo(wallet.toString());
    }

    private void txn(Account account, LocalDate date, TransactionSource source) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new java.math.BigDecimal("10.00"));
        t.setType(TransactionType.DEBIT);
        t.setSource(source);
        t.setCategoryKey("food");
        t.setReviewType(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        transactionRepository.save(t);
    }
}
