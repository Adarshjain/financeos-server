package com.financeos.api.lending;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.lending.CounterpartyRepository;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ledger entry kind end to end: principal vs settlement feed different gross totals on the
 * counterparty, while net position and the loans summary net every entry by money direction.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LendingKindIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CounterpartyRepository counterpartyRepository;
    @Autowired private LendingRepository lendingRepository;

    private Cookie session;
    private User user;
    private UUID counterpartyId;
    private final List<UUID> lendingIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String email = "lending-kind-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "lendingKindPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        String body = mockMvc.perform(post("/api/v1/counterparties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Rahul Sharma")))
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        counterpartyId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    /** Shared H2 across @SpringBootTest classes — remove every row this test created. */
    @AfterEach
    void tearDown() {
        lendingRepository.deleteAllById(lendingIds);
        counterpartyRepository.deleteById(counterpartyId);
        userRepository.deleteById(user.getId());
    }

    private Cookie authenticate(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email,
                        "password", password,
                        "inviteCode", "test-invite-code"))));

        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("FINANCEOS_SESSION");
    }

    private JsonNode postLending(String direction, String kind, String amount) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("counterpartyId", counterpartyId.toString());
        body.put("direction", direction);
        body.put("amount", amount);
        body.put("entryDate", "2026-01-15");
        if (kind != null) {
            body.put("kind", kind);
        }
        String response = mockMvc.perform(post("/api/v1/lendings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .cookie(session))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        lendingIds.add(UUID.fromString(node.get("id").asText()));
        return node;
    }

    private JsonNode counterparty() throws Exception {
        String body = mockMvc.perform(get("/api/v1/counterparties").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("content").get(0);
    }

    private JsonNode summary() throws Exception {
        String body = mockMvc.perform(get("/api/v1/loans/summary").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static void assertMoney(String expected, JsonNode node, String field) {
        assertEquals(0, new BigDecimal(expected).compareTo(node.get(field).decimalValue()),
                field + " expected " + expected + " but was " + node.get(field));
    }

    @Test
    void kindDefaultsToPrincipalWhenOmitted() throws Exception {
        JsonNode created = postLending("lent", null, "5000");

        assertEquals("principal", created.get("kind").asText());
    }

    @Test
    void settlementKindIsStoredAndEchoed() throws Exception {
        JsonNode created = postLending("borrowed", "settlement", "2000");

        assertEquals("settlement", created.get("kind").asText());
        assertEquals("borrowed", created.get("direction").asText());
    }

    @Test
    void unknownKindIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "counterpartyId", counterpartyId.toString(),
                "direction", "lent",
                "kind", "refund",
                "amount", "100",
                "entryDate", "2026-01-15");

        mockMvc.perform(post("/api/v1/lendings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .cookie(session))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void grossTotalsSplitByKindWhileNetAndSummaryCountEveryEntry() throws Exception {
        postLending("lent", "principal", "5000");      // you lent 5000
        postLending("borrowed", "settlement", "2000"); // they paid 2000 back
        postLending("borrowed", "principal", "1000");  // you borrowed 1000
        postLending("lent", "settlement", "1000");     // you paid the 1000 back

        JsonNode cp = counterparty();
        assertMoney("5000", cp, "totalLent");
        assertMoney("1000", cp, "totalBorrowed");
        assertMoney("2000", cp, "repaidToYou");
        assertMoney("1000", cp, "repaidByYou");
        assertMoney("3000", cp, "netPosition");
        assertEquals(4, cp.get("entryCount").asInt());

        JsonNode totals = summary();
        assertMoney("3000", totals, "lentOutstanding");
        assertMoney("0", totals, "borrowedOutstanding");
        assertMoney("3000", totals, "netReceivable");
    }

    @Test
    void flippingKindOnUpdateMovesTheGrossTotalWithoutChangingNet() throws Exception {
        postLending("lent", "principal", "5000");
        JsonNode repayment = postLending("borrowed", null, "2000"); // recorded the old way, as a borrow

        JsonNode before = counterparty();
        assertMoney("2000", before, "totalBorrowed");
        assertMoney("0", before, "repaidToYou");
        assertMoney("3000", before, "netPosition");

        mockMvc.perform(put("/api/v1/lendings/{id}", repayment.get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("kind", "settlement")))
                        .cookie(session))
                .andExpect(status().is2xxSuccessful());

        JsonNode after = counterparty();
        assertMoney("0", after, "totalBorrowed");
        assertMoney("2000", after, "repaidToYou");
        assertMoney("3000", after, "netPosition");
    }
}
