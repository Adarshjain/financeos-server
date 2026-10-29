package com.financeos.api.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.time.LocalDate;
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

/** GET /instruments/{id}/prices: without maxPoints the whole history, with it an even thinning that keeps both ends. */
@SpringBootTest
@AutoConfigureMockMvc
class PriceHistoryDownsampleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private UUID userId;
    private UUID instrument;
    private final List<String> dates = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String email = "prices-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        instrument = api.create("/api/v1/instruments", Map.of("type", "stock", "name", "Thin " + tag,
                "symbol", "THN" + tag.substring(0, 4), "exchange", "NSE", "isin", "INE" + tag + "T1"));
        LocalDate today = AppTime.today();
        for (int i = 0; i < 20; i++) {
            LocalDate d = today.minusDays(i);
            api.postJson("/api/v1/instruments/" + instrument + "/price", Map.of("price", 100 + i, "asOf", d.toString()), 200);
            dates.add(d.toString()); // newest first, like the response
        }
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
        UserDataCleanup.deleteInstruments(jdbc, List.of(instrument));
    }

    private List<String> asOf(String query) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode p : api.getJson("/api/v1/instruments/" + instrument + "/prices" + query, 200)) {
            out.add(p.get("asOf").asText());
        }
        return out;
    }

    @Test
    void withoutMaxPointsTheWholeHistoryIsReturnedAsBefore() throws Exception {
        assertEquals(dates, asOf(""));
    }

    @Test
    void maxPointsKeepsTheNewestAndOldestAndNeverExceedsTheLimit() throws Exception {
        List<String> five = asOf("?maxPoints=5");
        assertEquals(5, five.size());
        assertEquals(dates.get(0), five.get(0));
        assertEquals(dates.get(19), five.get(4));
        assertEquals(List.of(dates.get(0), dates.get(5), dates.get(10), dates.get(14), dates.get(19)), five);
        assertEquals(List.of(dates.get(0), dates.get(19)), asOf("?maxPoints=2"));
    }

    @Test
    void maxPointsAtOrAboveTheHistoryLengthReturnsEverything() throws Exception {
        assertEquals(dates, asOf("?maxPoints=20"));
        assertEquals(dates, asOf("?maxPoints=500"));
    }

    @Test
    void maxPointsBelowTwoIsRejected() throws Exception {
        api.getJson("/api/v1/instruments/" + instrument + "/prices?maxPoints=1", 400);
        api.getJson("/api/v1/instruments/" + instrument + "/prices?maxPoints=0", 400);
    }
}
