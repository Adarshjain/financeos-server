package com.financeos.api.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.util.ArrayList;
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

/**
 * GET /counterparties?outstanding=true&amp;sort=net against real persistence: the grouped net query
 * nets principal and settlements by money direction, only the caller's people are listed, and
 * paging applies after the filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CounterpartyOutstandingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private ApiTestClient other;
    private UUID userId;
    private UUID otherId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "cp-outstanding-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "cp-outstanding-other-" + UUID.randomUUID() + "@example.test";
        other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();

        UUID asha = person(api, "Asha");
        entry(api, asha, "lent", "principal", "5000");
        entry(api, asha, "borrowed", "settlement", "5000");      // settled up
        entry(api, person(api, "Bala"), "lent", "principal", "1000");
        UUID chirag = person(api, "Chirag");
        entry(api, chirag, "borrowed", "principal", "4000");
        entry(api, chirag, "lent", "settlement", "1000");        // you repaid 1,000: still owe 3,000
        entry(api, person(api, "Dev"), "lent", "principal", "3000");
        person(api, "Esha");                                     // no entries
        entry(other, person(other, "Zed"), "lent", "principal", "99999");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private static UUID person(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/counterparties", Map.of("name", name));
    }

    private static void entry(ApiTestClient client, UUID counterparty, String direction, String kind, String amount)
            throws Exception {
        client.postJson("/api/v1/lendings", Map.of("counterpartyId", counterparty.toString(), "direction", direction,
                "kind", kind, "amount", amount, "entryDate", "2026-01-15"), 200);
    }

    private JsonNode list(String query) throws Exception {
        return api.getJson("/api/v1/counterparties" + query, 200);
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("content").forEach(n -> names.add(n.get("name").asText()));
        return names;
    }

    @Test
    void outstandingByNetListsNonzeroBalancesLargestFirst() throws Exception {
        JsonNode page = list("?outstanding=true&sort=net");

        assertEquals(List.of("Chirag", "Dev", "Bala"), names(page));
        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(-3000, page.get("content").get(0).get("netPosition").asInt());
    }

    @Test
    void outstandingKeepsTheNameOrderByDefault() throws Exception {
        assertEquals(List.of("Bala", "Chirag", "Dev"), names(list("?outstanding=true")));
    }

    @Test
    void netSortAloneListsEveryoneWithSettledPeopleLast() throws Exception {
        assertEquals(List.of("Chirag", "Dev", "Bala", "Asha", "Esha"), names(list("?sort=net")));
    }

    @Test
    void pagesComeAfterTheFilter() throws Exception {
        JsonNode second = list("?outstanding=true&sort=net&page=1&size=2");

        assertEquals(List.of("Bala"), names(second));
        assertEquals(3, second.get("totalElements").asInt());
    }

    @Test
    void qStillNarrowsByName() throws Exception {
        assertEquals(List.of("Dev"), names(list("?outstanding=true&sort=net&q=de")));
    }

    @Test
    void withoutTheNewOptionsTheListIsUnchanged() throws Exception {
        assertEquals(List.of("Asha", "Bala", "Chirag", "Dev", "Esha"), names(list("")));
    }
}
