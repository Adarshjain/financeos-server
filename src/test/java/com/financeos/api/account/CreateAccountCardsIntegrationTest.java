package com.financeos.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** POST /accounts with add-on, earlier and debit cards: saved with the account, in one transaction. */
@SpringBootTest
@AutoConfigureMockMvc
class CreateAccountCardsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        String email = "create-cards-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    private Map<String, Object> creditCard() {
        Map<String, Object> body = new HashMap<>(Map.of("type", "credit_card", "name", "Cards Card",
                "last4", "1111", "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        return body;
    }

    private Map<String, Object> bankAccount() {
        return new HashMap<>(Map.of("type", "bank_account", "name", "Cards Bank", "openingBalance", 0,
                "last4", "9999", "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private long accountCount() {
        return jdbc.queryForObject("select count(*) from accounts where user_id = ?", Long.class, user.getId());
    }

    @Test
    void addonIsSavedWithRelationshipAndName() throws Exception {
        Map<String, Object> body = creditCard();
        body.put("addonCards", List.of(Map.of("last4", "2222", "relationship", "SPOUSE", "holderName", "Asha")));
        UUID id = api.create("/api/v1/accounts", body);

        JsonNode holders = api.getJson("/api/v1/accounts/" + id + "/cardholders", 200);
        assertEquals(2, holders.size());
        JsonNode addon = null;
        for (JsonNode h : holders) {
            if ("ADDON".equals(h.get("role").asText())) addon = h;
        }
        assertTrue(addon != null);
        assertEquals("SPOUSE", addon.get("relationship").asText());
        assertEquals("Asha", addon.get("personName").asText());
        assertEquals("2222", addon.get("cards").get(0).get("last4").asText());
        assertTrue(addon.get("cards").get(0).get("closedOn") == null || addon.get("cards").get(0).get("closedOn").isNull());
    }

    @Test
    void earlierCardIsSavedClosedOnThePrimaryHolder() throws Exception {
        Map<String, Object> body = creditCard();
        body.put("earlierCards", List.of(Map.of("last4", "3333", "closedOn", "2026-03-01"), Map.of("last4", "4444")));
        UUID id = api.create("/api/v1/accounts", body);

        JsonNode holders = api.getJson("/api/v1/accounts/" + id + "/cardholders", 200);
        assertEquals(1, holders.size());
        JsonNode cards = holders.get(0).get("cards");
        assertEquals(3, cards.size());
        for (JsonNode c : cards) {
            switch (c.get("last4").asText()) {
                case "1111" -> assertTrue(c.get("closedOn") == null || c.get("closedOn").isNull());
                case "3333" -> assertEquals("2026-03-01", c.get("closedOn").asText());
                case "4444" -> assertEquals(AppTime.today().toString(), c.get("closedOn").asText());
                default -> throw new AssertionError("unexpected card " + c);
            }
        }
    }

    @Test
    void debitCardIsSavedOpenOnThePrimaryHolderOfABankAccount() throws Exception {
        Map<String, Object> body = bankAccount();
        body.put("debitCard", Map.of("last4", "5555"));
        UUID id = api.create("/api/v1/accounts", body);

        JsonNode holders = api.getJson("/api/v1/accounts/" + id + "/cardholders", 200);
        assertEquals(1, holders.size());
        assertEquals("PRIMARY", holders.get(0).get("role").asText());
        assertEquals("SELF", holders.get(0).get("relationship").asText());
        assertEquals(1, holders.get(0).get("cards").size());
        assertEquals("5555", holders.get(0).get("cards").get(0).get("last4").asText());
        assertTrue(holders.get(0).get("cards").get(0).get("closedOn").isNull());
    }

    @Test
    void debitCardWithEarlierNumbersSavesTheEarlierOnesClosed() throws Exception {
        Map<String, Object> body = bankAccount();
        body.put("debitCard", Map.of("last4", "5555"));
        body.put("earlierCards", List.of(Map.of("last4", "6666", "closedOn", "2026-02-01")));
        UUID id = api.create("/api/v1/accounts", body);

        JsonNode cards = api.getJson("/api/v1/accounts/" + id + "/cardholders", 200).get(0).get("cards");
        assertEquals(2, cards.size());
        for (JsonNode c : cards) {
            if ("5555".equals(c.get("last4").asText())) assertTrue(c.get("closedOn").isNull());
            else assertEquals("2026-02-01", c.get("closedOn").asText());
        }
    }

    @Test
    void earlierNumbersOnlyStillCreateThePrimaryHolder() throws Exception {
        Map<String, Object> body = bankAccount();
        body.put("earlierCards", List.of(Map.of("last4", "6666")));
        UUID id = api.create("/api/v1/accounts", body);

        JsonNode holders = api.getJson("/api/v1/accounts/" + id + "/cardholders", 200);
        assertEquals(1, holders.size());
        assertEquals("SELF", holders.get(0).get("relationship").asText());
        assertEquals(AppTime.today().toString(), holders.get(0).get("cards").get(0).get("closedOn").asText());
    }

    @Test
    void addonHolderLooksLikeOneTheServiceWouldCreate() throws Exception {
        Map<String, Object> body = creditCard();
        body.put("addonCards", List.of(Map.of("last4", "2222", "relationship", "SPOUSE")));
        UUID id = api.create("/api/v1/accounts", body);
        JsonNode holders = api.getJson("/api/v1/accounts/" + id + "/cardholders", 200);
        String addonId = null;
        for (JsonNode h : holders) if ("ADDON".equals(h.get("role").asText())) addonId = h.get("id").asText();

        JsonNode error = api.postJson("/api/v1/accounts/" + id + "/cardholders/" + addonId + "/cards",
                Map.of("last4", "9999"), 400);
        assertTrue(error.get("message").asText().contains("already has an active card"));
    }

    @Test
    void duplicateLast4RejectedAndNothingSaved() throws Exception {
        Map<String, Object> body = creditCard();
        body.put("addonCards", List.of(Map.of("last4", "1111", "relationship", "CHILD")));
        JsonNode error = api.postJson("/api/v1/accounts", body, 400);
        assertTrue(error.get("message").asText().contains("1111"));

        Map<String, Object> bank = bankAccount();
        bank.put("debitCard", Map.of("last4", "5555"));
        bank.put("earlierCards", List.of(Map.of("last4", "5555")));
        api.postJson("/api/v1/accounts", bank, 400);
        assertEquals(0, accountCount());
    }

    @Test
    void addonWithoutRelationshipOrSelfIsRejectedAndNothingSaved() throws Exception {
        Map<String, Object> body = creditCard();
        body.put("addonCards", List.of(Map.of("last4", "2222")));
        api.postJson("/api/v1/accounts", body, 400);
        body.put("addonCards", List.of(Map.of("last4", "2222", "relationship", "SELF")));
        api.postJson("/api/v1/accounts", body, 400);
        assertEquals(0, accountCount());
    }

    @Test
    void emptyListsBehaveAsBefore() throws Exception {
        Map<String, Object> body = creditCard();
        body.put("addonCards", List.of());
        body.put("earlierCards", List.of());
        UUID id = api.create("/api/v1/accounts", body);
        JsonNode holders = api.getJson("/api/v1/accounts/" + id + "/cardholders", 200);
        assertEquals(1, holders.size());
        assertEquals(1, holders.get(0).get("cards").size());

        Map<String, Object> bank = bankAccount();
        bank.put("earlierCards", List.of());
        UUID bankId = api.create("/api/v1/accounts", bank);
        assertEquals(0, api.getJson("/api/v1/accounts/" + bankId + "/cardholders", 200).size());
    }
}
