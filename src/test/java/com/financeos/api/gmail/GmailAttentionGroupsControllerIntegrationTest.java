package com.financeos.api.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import com.financeos.core.time.AppTime;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The grouped "Needs attention" endpoints and the past-card / seen-dates endpoints through HTTP: shapes and the whole flow. */
@SpringBootTest
@AutoConfigureMockMvc
class GmailAttentionGroupsControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;

    private ApiTestClient api;
    private User user;
    private GmailConnection connection;
    private UUID savings;

    @BeforeEach
    void setUp() throws Exception {
        String email = "attention-groups-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        connection = new GmailConnection();
        connection.setUser(user);
        connection.setEmail(email);
        connection.setEncryptedRefreshToken("token");
        connection = connectionRepository.save(connection);
        savings = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "HDFC Savings",
                "financialPosition", "asset", "excludeFromNetAsset", false));
        Account account = accountRepository.findById(savings).orElseThrow();
        account.setIngestFromDate(LocalDate.of(2026, 10, 7));
        accountRepository.save(account);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    private GmailProcessedMessage parked(String last4, String internalDate) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        m.setExtractedLast4(last4);
        m.setSenderAddress("alerts@hdfcbank.net");
        m.setInternalDate(Instant.parse(internalDate));
        m.setError("No single account matches card/account ending " + last4);
        return messageRepository.save(m);
    }

    @Test
    void groupsThenIgnoreThenRestore() throws Exception {
        parked("3003", "2024-01-01T00:00:00Z");
        parked("3003", "2025-04-01T00:00:00Z");
        parked(null, "2025-05-01T00:00:00Z");

        JsonNode groups = api.getJson("/api/v1/gmail/attention/groups", 200);
        assertEquals(1, groups.get("unknownNumbers").size());
        JsonNode group = groups.get("unknownNumbers").get(0);
        assertEquals("hdfc", group.get("bank").asText());
        assertEquals("3003", group.get("last4").asText());
        assertEquals(2, group.get("count").asInt());
        assertEquals("ALERT", group.get("kinds").get(0).asText());
        assertTrue(group.get("suggestion").isNull());
        assertTrue(groups.get("importOff").isArray());
        assertEquals(0, groups.get("ignored").size());

        JsonNode individual = api.getJson("/api/v1/gmail/attention/ungrouped", 200);
        assertEquals(1, individual.get("totalElements").asInt()); // only the mail with no number

        JsonNode ignored = api.postJson("/api/v1/gmail/attention/numbers/ignore", Map.of("bank", "hdfc", "last4", "3003"), 200);
        assertEquals(2, ignored.get("count").asInt());
        JsonNode after = api.getJson("/api/v1/gmail/attention/groups", 200);
        assertEquals(0, after.get("unknownNumbers").size());
        assertEquals(2, after.get("ignored").get(0).get("count").asInt());
        JsonNode archived = api.getJson("/api/v1/gmail/attention/ungrouped?archived=true", 200);
        assertEquals(2, archived.get("totalElements").asInt());

        String ruleId = after.get("ignored").get(0).get("id").asText();
        JsonNode restored = api.deleteJson("/api/v1/gmail/attention/numbers/ignored/" + ruleId, 200);
        assertEquals(2, restored.get("count").asInt());
        assertEquals(1, api.getJson("/api/v1/gmail/attention/groups", 200).get("unknownNumbers").size());
    }

    @Test
    void ungroupedFiltersByReason_summaryHasUngroupedOption_totalOnGroups_andBulkArchiveSkipsGroups() throws Exception {
        parked("3003", "2025-04-01T00:00:00Z"); // grouped
        parked(null, "2025-05-01T00:00:00Z"); // NO_ACCOUNT, ungrouped
        GmailProcessedMessage locked = parked(null, "2025-06-01T00:00:00Z");
        locked.setStatus(GmailProcessedStatus.FAILED_PERMANENT);
        locked.setError("Attachment could not be decrypted");
        messageRepository.save(locked);

        assertEquals(3, api.getJson("/api/v1/gmail/attention/groups", 200).get("total").asInt()); // 1 group + 2 emails
        assertEquals(1, api.getJson("/api/v1/gmail/attention/ungrouped?reason=LOCKED_STATEMENT", 200).get("totalElements").asInt());
        assertEquals(1, api.getJson("/api/v1/gmail/attention/ungrouped?reason=NO_ACCOUNT", 200).get("totalElements").asInt());
        assertEquals(0, api.getJson("/api/v1/gmail/attention/ungrouped?reason=NO_AI_KEY", 200).get("totalElements").asInt());
        JsonNode all = api.getJson("/api/v1/gmail/attention/summary", 200);
        JsonNode ungrouped = api.getJson("/api/v1/gmail/attention/summary?ungroupedOnly=true", 200);
        int allNoAccount = 0;
        for (JsonNode c : all) if (c.get("reason").asText().equals("NO_ACCOUNT")) allNoAccount = c.get("count").asInt();
        int ungroupedNoAccount = 0;
        for (JsonNode c : ungrouped) if (c.get("reason").asText().equals("NO_ACCOUNT")) ungroupedNoAccount = c.get("count").asInt();
        assertEquals(2, allNoAccount); // the numbered mail counts in the full summary
        assertEquals(1, ungroupedNoAccount); // and not in the ungrouped one
        assertEquals(2, ungrouped.size());

        JsonNode done = api.postJson("/api/v1/gmail/attention/archive-all", Map.of("reason", "NO_ACCOUNT", "ungroupedOnly", true), 200);
        assertEquals(1, done.get("affected").asInt());
        assertEquals(1, api.getJson("/api/v1/gmail/attention/groups", 200).get("unknownNumbers").size());
    }

    @Test
    void assignGroupToAnAccount() throws Exception {
        GmailProcessedMessage old = parked("3003", "2021-05-10T00:00:00Z");
        GmailProcessedMessage inWindow = parked("3003", "2026-10-20T00:00:00Z");

        JsonNode result = api.postJson("/api/v1/gmail/attention/numbers/assign",
                Map.of("bank", "hdfc", "last4", "3003", "accountId", savings.toString()), 200);

        assertEquals(1, result.get("reactivatedCount").asInt());
        assertEquals(1, result.get("skippedCount").asInt());
        assertEquals(0, result.get("remainingCount").asInt());
        assertFalse(result.get("identifierId").isNull());
        assertEquals(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK, messageRepository.findById(old.getId()).orElseThrow().getStatus());
        assertEquals(GmailProcessedStatus.DISCOVERED, messageRepository.findById(inWindow.getId()).orElseThrow().getStatus());
        api.postJson("/api/v1/gmail/attention/numbers/assign", Map.of("bank", "hdfc", "last4", "12", "accountId", savings.toString()), 400);
    }

    @Test
    void pastCard_onAHolderWithAnActiveCard_thenSeenDates() throws Exception {
        UUID holder = api.create("/api/v1/accounts/" + savings + "/cardholders/primary", Map.of("last4", "1111", "issuedOn", "2025-05-01"));
        parked("3003", "2021-05-10T00:00:00Z");

        api.postJson("/api/v1/accounts/" + savings + "/cardholders/" + holder + "/cards",
                Map.of("last4", "3003", "issuedOn", "2021-05-01"), 400); // an ordinary second card still needs a replace
        JsonNode added = api.postJson("/api/v1/accounts/" + savings + "/cardholders/" + holder + "/cards",
                Map.of("last4", "3003", "issuedOn", "2021-05-01", "closedOn", "2025-04-30"), 201);
        assertEquals(2, added.get("cards").size());
        api.postJson("/api/v1/accounts/" + savings + "/cardholders/" + holder + "/cards",
                Map.of("last4", "1111", "issuedOn", "2021-05-01", "closedOn", "2025-04-30"), 400); // shares digits with the open card
        api.postJson("/api/v1/accounts/" + savings + "/cardholders/" + holder + "/cards",
                Map.of("last4", "2222", "issuedOn", "2021-05-01", "closedOn", AppTime.today().plusDays(1).toString()), 400);

        JsonNode seen = api.getJson("/api/v1/accounts/" + savings + "/cards/seen-dates?last4=3003", 200);
        assertEquals("2021-05-10", seen.get("firstSeen").asText());
        assertEquals("2021-05-10", seen.get("lastSeen").asText());
        assertEquals(1, seen.get("mailCount").asInt());
        assertEquals(0, seen.get("transactionCount").asInt());
        api.getJson("/api/v1/accounts/" + savings + "/cards/seen-dates?last4=abc", 400);
    }
}
