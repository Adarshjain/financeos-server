package com.financeos.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DELETE /accounts/{id}/cardholders/{id}/cards/{id} and the "opened on" edit on PUT /cardholders/{id},
 * over real persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CardholderCardDeleteIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CardRepository cardRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private User user;
    private UUID otherUserId;
    private UUID accountId;
    private String base;
    private UUID holderId;
    private UUID firstCard;

    @BeforeEach
    void setUp() throws Exception {
        String email = "card-delete-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        String otherEmail = "card-delete-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        accountId = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Delete Card",
                "last4", "1111", "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        base = "/api/v1/accounts/" + accountId + "/cardholders";
        JsonNode holder = api.getJson(base, 200).get(0);
        holderId = UUID.fromString(holder.get("id").asText());
        firstCard = UUID.fromString(holder.get("cards").get(0).get("id").asText());
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), otherUserId));
    }

    @Test
    void replacedCardIsDeletedAndItsTransactionsMoveToTheActiveCard() throws Exception {
        UUID active = replace("2222", "2026-05-01");
        UUID tx = tx(firstCard);

        api.deleteJson(base + "/" + holderId + "/cards/" + firstCard, 204);

        assertFalse(cardRepository.existsById(firstCard));
        assertEquals(active, transactionRepository.findById(tx).orElseThrow().getCard().getId());
        JsonNode holder = api.getJson(base, 200).get(0);
        assertEquals(1, holder.get("cards").size());
        assertEquals(1, holder.get("transactionCount").asInt(), "the transaction is kept");
    }

    @Test
    void withNoActiveCardTransactionsGoToTheMostRecentlyIssuedOtherCard() throws Exception {
        UUID second = replace("2222", "2026-05-01");
        UUID third = replace("3333", "2026-08-01");
        api.postJson(base + "/" + holderId + "/cards/" + third + "/close", Map.of("closedOn", "2026-09-01"), 200);
        UUID tx = tx(firstCard);

        api.deleteJson(base + "/" + holderId + "/cards/" + firstCard, 204);

        assertEquals(third, transactionRepository.findById(tx).orElseThrow().getCard().getId(), "newest issued, not " + second);
        assertTrue(cardRepository.existsById(second));
    }

    @Test
    void activeCardCannotBeDeleted() throws Exception {
        UUID active = replace("2222", "2026-05-01");

        JsonNode error = api.deleteJson(base + "/" + holderId + "/cards/" + active, 400);

        assertEquals("Close or replace this card first.", error.get("message").asText());
        assertTrue(cardRepository.existsById(active));
    }

    @Test
    void onlyCardCannotBeDeleted() throws Exception {
        api.postJson(base + "/" + holderId + "/cards/" + firstCard + "/close", Map.of(), 200);

        JsonNode error = api.deleteJson(base + "/" + holderId + "/cards/" + firstCard, 400);

        assertEquals("This is the holder's only card. Delete the cardholder instead.", error.get("message").asText());
        assertTrue(cardRepository.existsById(firstCard));
    }

    @Test
    void cardOfAnotherHolderIsRejected() throws Exception {
        replace("2222", "2026-05-01");
        UUID addon = api.create(base, Map.of("personName", "Asha", "relationship", "SPOUSE", "last4", "4444"));

        api.deleteJson(base + "/" + addon + "/cards/" + firstCard, 400);
        assertTrue(cardRepository.existsById(firstCard));
    }

    @Test
    void wrongAccountOrAnotherUserIsRejected() throws Exception {
        replace("2222", "2026-05-01");
        UUID otherAccount = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Other Card",
                "last4", "7777", "creditLimit", 1000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));

        api.deleteJson("/api/v1/accounts/" + otherAccount + "/cardholders/" + holderId + "/cards/" + firstCard, 404);
        otherApi.deleteJson(base + "/" + holderId + "/cards/" + firstCard, 400);
        assertTrue(cardRepository.existsById(firstCard));
    }

    @Test
    void openedOnIsSet() throws Exception {
        JsonNode holder = api.putJson(base + "/" + holderId, Map.of("openedOn", "2025-01-10"), 200);

        assertEquals("2025-01-10", holder.get("openedOn").asText());
    }

    @Test
    void openedOnAbsentLeavesItUnchanged() throws Exception {
        api.putJson(base + "/" + holderId, Map.of("openedOn", "2025-01-10"), 200);

        JsonNode holder = api.putJson(base + "/" + holderId, Map.of("spendLimit", 5000), 200);

        assertEquals("2025-01-10", holder.get("openedOn").asText());
    }

    @Test
    void openedOnInTheFutureIsRejected() throws Exception {
        api.putJson(base + "/" + holderId, Map.of("openedOn", AppTime.today().plusDays(1).toString()), 400);
    }

    @Test
    void openedOnAfterClosedOnIsRejected() throws Exception {
        UUID addon = api.create(base, Map.of("personName", "Asha", "relationship", "SPOUSE", "last4", "4444"));
        api.postJson(base + "/" + addon + "/close", Map.of("closedOn", "2026-03-01"), 200);

        api.putJson(base + "/" + addon, Map.of("openedOn", "2026-03-02"), 400);
        JsonNode ok = api.putJson(base + "/" + addon, Map.of("openedOn", "2026-03-01"), 200);

        assertEquals("2026-03-01", ok.get("openedOn").asText());
    }

    // helpers

    /** Replaces the holder's open card; returns the new card's id. */
    private UUID replace(String newLast4, String issuedOn) throws Exception {
        UUID open = UUID.fromString(api.getJson(base, 200).get(0).get("cards").get(0).get("id").asText());
        JsonNode holder = api.postJson(base + "/" + holderId + "/cards/" + open + "/replace",
                Map.of("newLast4", newLast4, "issuedOn", issuedOn), 200);
        return UUID.fromString(holder.get("cards").get(0).get("id").asText());
    }

    private UUID tx(UUID cardId) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(accountRepository.findById(accountId).orElseThrow());
        t.setCard(cardRepository.findById(cardId).orElseThrow());
        t.setDate(LocalDate.of(2026, 2, 1));
        t.setAmount(new BigDecimal("10"));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setSourcedDescription("TEST");
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        return transactionRepository.save(t).getId();
    }
}
