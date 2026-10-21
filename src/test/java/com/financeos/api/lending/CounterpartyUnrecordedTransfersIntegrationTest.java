package com.financeos.api.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.time.LocalDate;
import java.util.HashMap;
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

/** GET /counterparties/unrecorded-transfers and POST /counterparties/{id}/settlements against real persistence. */
@SpringBootTest
@AutoConfigureMockMvc
class CounterpartyUnrecordedTransfersIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private ApiTestClient other;
    private UUID userId;
    private UUID otherId;
    private UUID bank;
    private UUID asha;
    private LocalDate day;

    @BeforeEach
    void setUp() throws Exception {
        String email = "cp-unrec-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "cp-unrec-other-" + UUID.randomUUID() + "@example.test";
        other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        day = AppTime.today().minusDays(2);
        bank = bank(api, "Unrec Bank");
        asha = api.create("/api/v1/counterparties", Map.of("name", "Asha"));   // owes you 1000
        entry(api, asha, "lent", "principal", "1000");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private static UUID bank(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", "4321",
                "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static void entry(ApiTestClient client, UUID cp, String direction, String kind, String amount) throws Exception {
        client.postJson("/api/v1/lendings", Map.of("counterpartyId", cp.toString(), "direction", direction,
                "kind", kind, "amount", amount, "entryDate", "2026-01-10"), 200);
    }

    private static UUID txn(ApiTestClient client, UUID account, LocalDate date, int signedAmount, String description) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", account.toString());
        body.put("date", date.toString());
        body.put("amount", signedAmount);
        body.put("description", description);
        return client.create("/api/v1/transactions", body);
    }

    private JsonNode offered() throws Exception {
        return api.getJson("/api/v1/counterparties/unrecorded-transfers", 200);
    }

    private static String settlementBody(UUID txn) {
        return "{\"transactionId\":\"" + txn + "\"}";
    }

    @Test
    void listsASeededTransferWithItsSettlementDirection() throws Exception {
        UUID txn = txn(api, bank, day, 400, "UPI/ASHA/ref");

        JsonNode list = offered();

        assertTrue(list.isArray());
        assertEquals(1, list.size());
        assertEquals(asha.toString(), list.get(0).get("counterpartyId").asText());
        assertEquals("Asha", list.get(0).get("counterpartyName").asText());
        assertEquals(txn.toString(), list.get(0).get("transaction").get("id").asText());
        assertEquals("borrowed", list.get(0).get("direction").asText());
    }

    @Test
    void leavesOutOtherPeoplesTransfersCardsOldOnesAndWrongWayOnes() throws Exception {
        txn(api, bank, day, -400, "to Asha");                              // debit while she owes you: grows it
        txn(api, bank, day, 400, "from somebody else");                    // names nobody
        txn(api, bank, AppTime.today().minusDays(91), 400, "Asha old");     // outside 90 days
        UUID card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Unrec Card", "last4", "9999",
                "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        txn(api, card, day, 400, "Asha refund on card");
        txn(other, bank(other, "Other Bank"), day, 400, "Asha");           // another user's

        assertEquals(0, offered().size());
    }

    @Test
    void recordingASettlementLinksItAndRemovesItFromTheList() throws Exception {
        UUID txn = txn(api, bank, day, 400, "UPI/ASHA/ref");

        JsonNode created = api.postJson("/api/v1/counterparties/" + asha + "/settlements", Map.of("transactionId", txn.toString()), 200);

        assertEquals("settlement", created.get("kind").asText());
        assertEquals("borrowed", created.get("direction").asText());
        assertEquals(400, created.get("amount").asDouble(), 0.0001);
        assertEquals(day.toString(), created.get("entryDate").asText());
        assertEquals(txn.toString(), created.get("transactionId").asText());
        assertEquals(0, offered().size());
        JsonNode ledger = api.getJson("/api/v1/counterparties/" + asha + "/ledger", 200);
        assertEquals(2, ledger.get("entries").get("totalElements").asInt());
        assertEquals(600, ledger.get("totals").get("closing").asDouble(), 0.0001);
        assertEquals(txn.toString(), ledger.get("entries").get("content").get(0).get("transactionId").asText());
    }

    @Test
    void settlementErrors() throws Exception {
        UUID txn = txn(api, bank, day, 400, "UPI/ASHA/ref");
        UUID wrongWay = txn(api, bank, day, -400, "to Asha");
        UUID tooBig = txn(api, bank, day, 1021, "Asha big");
        String url = "/api/v1/counterparties/" + asha + "/settlements";

        api.postJson(url, Map.of("transactionId", UUID.randomUUID().toString()), 404);
        api.postJson("/api/v1/counterparties/" + UUID.randomUUID() + "/settlements", Map.of("transactionId", txn.toString()), 404);
        api.postJson(url, Map.of("transactionId", wrongWay.toString()), 400);
        api.postJson(url, Map.of("transactionId", tooBig.toString()), 400);
        api.postJson(url, Map.of("transactionId", txn.toString()), 200);
        api.postJson(url, Map.of("transactionId", txn.toString()), 409);
        api.postJson(url, Map.of(), 400);
    }

    @Test
    void tenancyForeignTransactionAndForeignPersonAre404() throws Exception {
        UUID foreignTxn = txn(other, bank(other, "Other Bank"), day, 400, "Asha");
        UUID zed = other.create("/api/v1/counterparties", Map.of("name", "Zed"));
        entry(other, zed, "lent", "principal", "1000");
        UUID mine = txn(api, bank, day, 400, "UPI/ASHA/ref");

        api.postJson("/api/v1/counterparties/" + asha + "/settlements", Map.of("transactionId", foreignTxn.toString()), 404);
        api.postJson("/api/v1/counterparties/" + zed + "/settlements", Map.of("transactionId", mine.toString()), 404);
        assertEquals(0, other.getJson("/api/v1/counterparties/unrecorded-transfers", 200).size());
        assertEquals(1, offered().size());
    }
}
