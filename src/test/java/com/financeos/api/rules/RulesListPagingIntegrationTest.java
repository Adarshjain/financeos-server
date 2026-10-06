package com.financeos.api.rules;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.MatchType;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /rules pages in SQL and loads categories for the page only — the fetch-joined paged query
 * used to make Hibernate load every rule and page in memory (HHH90003004).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RulesListPagingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryRuleRepository categoryRuleRepository;

    private Cookie session;
    private User user;
    private Category food;
    private Category travel;
    private final List<UUID> ruleIds = new ArrayList<>();

    // Sorted by appliedCount desc: busiest (food) → both (food+travel) → idle (travel)
    private CategoryRule busiest;
    private CategoryRule both;
    private CategoryRule idle;

    @BeforeEach
    void setUp() throws Exception {
        String email = "rules-paging-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        food = categoryRepository.save(new Category("Food", user));
        travel = categoryRepository.save(new Category("Travel", user));

        busiest = rule("ZOMATO", 9, food);
        both = rule("UBER", 5, food, travel);
        idle = rule("IRCTC", 0, travel);
    }

    /** Shared H2 across @SpringBootTest classes — remove every row this test created. */
    @AfterEach
    void tearDown() {
        categoryRuleRepository.deleteAllById(ruleIds);
        categoryRepository.deleteAllById(List.of(food.getId(), travel.getId()));
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

    private CategoryRule rule(String key, int appliedCount, Category... categories) {
        CategoryRule rule = new CategoryRule();
        rule.setUser(user);
        rule.setMerchantKey(key);
        rule.setMatchType(MatchType.MERCHANT_KEY);
        rule.setSource("USER");
        rule.setVerified(false);
        rule.setAppliedCount(appliedCount);
        rule.setCategories(new HashSet<>(Set.of(categories)));
        CategoryRule saved = categoryRuleRepository.save(rule);
        ruleIds.add(saved.getId());
        return saved;
    }

    private JsonNode page(int page, int size) throws Exception {
        String body = mockMvc.perform(get("/api/v1/rules")
                        .param("sort", "appliedCount,desc")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .cookie(session))
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

    /** Rule id → its category names, for every rule on the page. */
    private static Map<UUID, Set<String>> categoriesById(JsonNode page) {
        Map<UUID, Set<String>> out = new HashMap<>();
        for (JsonNode node : page.get("content")) {
            Set<String> names = new HashSet<>();
            node.get("categories").forEach(c -> names.add(c.get("name").asText()));
            out.put(UUID.fromString(node.get("id").asText()), names);
        }
        return out;
    }

    @Test
    void pagesFollowSortAndReportTotals() throws Exception {
        JsonNode first = page(0, 2);
        JsonNode second = page(1, 2);

        assertEquals(List.of(busiest.getId(), both.getId()), ids(first));
        assertEquals(List.of(idle.getId()), ids(second));
        assertEquals(3, first.get("totalElements").asInt());
        assertEquals(2, first.get("totalPages").asInt());
    }

    @Test
    void everyRuleOnEachPageCarriesAllItsCategories() throws Exception {
        assertEquals(Map.of(busiest.getId(), Set.of("Food"), both.getId(), Set.of("Food", "Travel")),
                categoriesById(page(0, 2)));
        assertEquals(Map.of(idle.getId(), Set.of("Travel")), categoriesById(page(1, 2)));
    }

    @Test
    void pageBeyondTheEndIsEmptyWithTotalsIntact() throws Exception {
        JsonNode beyond = page(5, 2);

        assertEquals(List.of(), ids(beyond));
        assertEquals(3, beyond.get("totalElements").asInt());
    }

    @Test
    void pagingIsNotAppliedInMemory() throws Exception {
        Logger hibernateQueryLog = (Logger) LoggerFactory.getLogger("org.hibernate.orm.query");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        hibernateQueryLog.addAppender(appender);
        try {
            page(0, 2);
        } finally {
            hibernateQueryLog.detachAppender(appender);
        }

        assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("HHH90003004")),
                "GET /rules must page in SQL, not fetch every rule and page in memory");
    }
}
