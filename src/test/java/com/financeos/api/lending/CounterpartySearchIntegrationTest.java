package com.financeos.api.lending;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.lending.CounterpartyRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /counterparties?q= narrows by name in SQL and GET /counterparties/suggest
 * pre-fills the lending picker from a transaction description — both scoped to
 * the caller's own people.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CounterpartySearchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CounterpartyRepository counterpartyRepository;

    private Cookie session;
    private Cookie otherSession;
    private User user;
    private User otherUser;
    private final List<UUID> counterpartyIds = new ArrayList<>();

    private UUID rahulSharma;
    private UUID rahulVerma;
    private UUID priya;

    @BeforeEach
    void setUp() throws Exception {
        String email = "cp-search-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "cpSearchPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        String otherEmail = "cp-search-other-" + UUID.randomUUID() + "@example.test";
        otherSession = authenticate(otherEmail, "cpSearchPass123!");
        otherUser = userRepository.findByEmail(otherEmail).orElseThrow();

        rahulSharma = createCounterparty(session, "Rahul Sharma");
        rahulVerma = createCounterparty(session, "Rahul Verma");
        priya = createCounterparty(session, "Priya Nair");
        // Same name under another tenant: must never leak into this user's search or suggestion.
        createCounterparty(otherSession, "Rahul Sharma");
    }

    /** Shared H2 across @SpringBootTest classes — remove every row this test created. */
    @AfterEach
    void tearDown() {
        counterpartyRepository.deleteAllById(counterpartyIds);
        userRepository.deleteById(user.getId());
        userRepository.deleteById(otherUser.getId());
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

    private UUID createCounterparty(Cookie asUser, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/counterparties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name)))
                        .cookie(asUser))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        counterpartyIds.add(id);
        return id;
    }

    private JsonNode search(String q) throws Exception {
        var request = get("/api/v1/counterparties").cookie(session);
        if (q != null) {
            request = request.param("q", q);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode suggest(String text) throws Exception {
        String body = mockMvc.perform(get("/api/v1/counterparties/suggest").param("text", text).cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : page.get("content")) {
            ids.add(UUID.fromString(node.get("id").asText()));
        }
        return ids;
    }

    @Test
    void withoutQListsEveryOwnCounterpartyByName() throws Exception {
        JsonNode page = search(null);

        assertEquals(List.of(priya, rahulSharma, rahulVerma), ids(page));
        assertEquals(3, page.get("totalElements").asInt());
    }

    @Test
    void blankQIsTreatedAsNoFilter() throws Exception {
        assertEquals(3, search("   ").get("totalElements").asInt());
    }

    @Test
    void qMatchesCaseInsensitiveSubstringsAndReportsFilteredTotals() throws Exception {
        JsonNode page = search("rAHUL");

        assertEquals(List.of(rahulSharma, rahulVerma), ids(page));
        assertEquals(2, page.get("totalElements").asInt());

        assertEquals(List.of(priya), ids(search("iya")));
    }

    @Test
    void qTrimsSurroundingWhitespace() throws Exception {
        assertEquals(List.of(rahulVerma), ids(search("  verma ")));
    }

    @Test
    void likeWildcardsInQAreLiteral() throws Exception {
        assertEquals(0, search("%").get("totalElements").asInt());
        assertEquals(0, search("_").get("totalElements").asInt());
    }

    @Test
    void qWithNoMatchIsEmpty() throws Exception {
        JsonNode page = search("nobody");

        assertEquals(List.of(), ids(page));
        assertEquals(0, page.get("totalElements").asInt());
    }

    @Test
    void searchNeverReturnsAnotherUsersCounterparties() throws Exception {
        for (UUID id : ids(search("Rahul Sharma"))) {
            assertEquals(rahulSharma, id);
        }
        assertEquals(1, search("Rahul Sharma").get("totalElements").asInt());
    }

    @Test
    void suggestReturnsTheBestOwnNameMatchWithItsTotals() throws Exception {
        JsonNode suggestion = suggest("UPI/Dinner with Rahul Sharma");

        JsonNode cp = suggestion.get("counterparty");
        assertEquals(rahulSharma, UUID.fromString(cp.get("id").asText()));
        assertEquals("Rahul Sharma", cp.get("name").asText());
        assertEquals(0, cp.get("entryCount").asInt());
    }

    @Test
    void suggestTieBreaksByNameOrder() throws Exception {
        // "Rahul" overlaps both Rahuls equally; the name-sorted list puts Sharma first.
        JsonNode cp = suggest("Paid rahul back").get("counterparty");
        assertEquals(rahulSharma, UUID.fromString(cp.get("id").asText()));
    }

    @Test
    void suggestWithoutOverlapIsNull() throws Exception {
        assertTrue(suggest("Swiggy order").get("counterparty").isNull());
    }

    @Test
    void suggestIsScopedToTheCallerEvenWhenAnotherTenantHasTheSameName() throws Exception {
        String body = mockMvc.perform(get("/api/v1/counterparties/suggest")
                        .param("text", "Priya Nair lunch")
                        .cookie(otherSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(objectMapper.readTree(body).get("counterparty").isNull());
    }
}
