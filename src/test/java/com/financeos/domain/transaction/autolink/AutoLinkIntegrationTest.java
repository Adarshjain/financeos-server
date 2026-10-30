package com.financeos.domain.transaction.autolink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Auto-linking end to end on H2: the candidate query, the engine, the store and the endpoints. The two
 * auto-link tables are not JPA entities, so this test creates them for itself and drops them afterwards;
 * without them a background run (every ingest triggers one) fails in its first read and links nothing,
 * which keeps every other integration test unaffected.
 */
@SpringBootTest(properties = "autolink.enabled=true")
@AutoConfigureMockMvc
class AutoLinkIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AutoLinkService autoLinkService;
    @Autowired private AutoLinkCandidateLoader loader;

    private ApiTestClient api;
    private UUID userId;
    private UUID bankA;
    private UUID bankB;
    private UUID bankC;

    @BeforeEach
    void setUp() throws Exception {
        String email = "autolink-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        bankA = bank("Bank A");
        bankB = bank("Bank B");
        bankC = bank("Bank C");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Let a background run finish before the user is deleted.
        Thread.sleep(500);
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private UUID bank(String name) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID txn(UUID account, int signedAmount, int daysAgo, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", AppTime.today().minusDays(daysAgo).toString());
        body.put("amount", signedAmount);
        body.put("description", description);
        return api.create("/api/v1/transactions", body);
    }

    private static void eventually(Supplier<Boolean> condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met in time");
    }

    private JsonNode linksOf(UUID transactionId) throws Exception {
        return api.getJson("/api/v1/transaction-links?transactionId=" + transactionId, 200);
    }

    private JsonNode suggestions() throws Exception {
        return api.getJson("/api/v1/link-suggestions", 200);
    }

    @Test
    void aUniquePairIsLinkedAutomaticallyAndUnlinkingItIsRemembered() throws Exception {
        UUID out = txn(bankA, -5000, 3, "To savings");
        UUID in = txn(bankB, 5000, 3, "From checking");

        eventually(() -> {
            try {
                return linksOf(out).size() == 1;
            } catch (Exception e) {
                return false;
            }
        });
        JsonNode link = linksOf(out).get(0);
        assertEquals("TRANSFER", link.get("type").asText());
        assertEquals("AUTO", link.get("createdBy").asText());

        api.deleteJson("/api/v1/transaction-links/" + link.get("id").asText(), 204);
        autoLinkService.run(userId);

        assertEquals(0, linksOf(out).size(), "an unlinked auto pair must not be linked again");
        assertEquals(0, linksOf(in).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) / 2 FROM auto_link_rejections WHERE user_id = ?", Integer.class, userId.toString()));
    }

    @Test
    void twoFittingCreditsMakeASuggestionTheUserCanAcceptAndOwnedRowsAreNotCandidates() throws Exception {
        UUID inB = txn(bankB, 700, 4, "Cash in B");
        UUID inC = txn(bankC, 700, 4, "Cash in C");
        UUID out = txn(bankA, -700, 4, "Cash out A");
        // A lending-owned debit must never be proposed.
        UUID lentOut = txn(bankA, -900, 4, "Lent");
        Map<String, Object> lending = new LinkedHashMap<>();
        lending.put("newCounterpartyName", "Ravi");
        lending.put("direction", "lent");
        lending.put("amount", 900);
        lending.put("entryDate", AppTime.today().minusDays(4).toString());
        lending.put("transactionId", lentOut.toString());
        api.postJson("/api/v1/lendings", lending, 200);
        txn(bankB, 900, 4, "Looks like its pair");

        eventually(() -> {
            try {
                return suggestions().size() == 1;
            } catch (Exception e) {
                return false;
            }
        });
        autoLinkService.run(userId);

        JsonNode list = suggestions();
        assertEquals(1, list.size());
        JsonNode suggestion = list.get(0);
        assertEquals("TRANSFER", suggestion.get("kind").asText());
        assertEquals(out.toString(), suggestion.get("transaction").get("id").asText());
        assertEquals(2, suggestion.get("candidates").size());
        assertEquals("Bank A", suggestion.get("transaction").get("accountName").asText());
        assertFalse(loader.load(userId).stream().anyMatch(c -> c.id().equals(lentOut)));
        assertEquals(0, linksOf(out).size());

        JsonNode accepted = api.postJson("/api/v1/link-suggestions/" + suggestion.get("id").asText() + "/accept",
                Map.of("candidateId", inB.toString()), 201);
        assertEquals("USER", accepted.get("createdBy").asText());
        assertEquals("TRANSFER", accepted.get("type").asText());
        assertEquals(0, suggestions().size());
        assertEquals(1, linksOf(inB).size());
        assertEquals(0, linksOf(inC).size());
    }

    @Test
    void dismissingASuggestionIsRemembered() throws Exception {
        txn(bankB, 300, 2, "Cash in B");
        txn(bankC, 300, 2, "Cash in C");
        UUID out = txn(bankA, -300, 2, "Cash out A");

        eventually(() -> {
            try {
                return suggestions().size() == 1;
            } catch (Exception e) {
                return false;
            }
        });
        JsonNode suggestion = suggestions().get(0);
        assertEquals(out.toString(), suggestion.get("transaction").get("id").asText());

        api.deleteJson("/api/v1/link-suggestions/" + suggestion.get("id").asText(), 204);
        autoLinkService.run(userId);

        assertEquals(0, suggestions().size());
        assertEquals(0, linksOf(out).size());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM auto_link_rejections WHERE user_id = ?", Integer.class, userId.toString()) >= 2);
    }

    @Test
    void anotherUsersSuggestionIsNotFound() throws Exception {
        api.deleteJson("/api/v1/link-suggestions/" + UUID.randomUUID(), 404);
    }
}
