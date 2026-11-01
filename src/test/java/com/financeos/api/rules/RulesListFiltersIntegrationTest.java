package com.financeos.api.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.MatchType;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /rules filters (matchType, applied, categoryKey) and the sort whitelist, run through
 * the real JPQL so a broken clause fails here rather than only on Oracle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RulesListFiltersIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRuleRepository categoryRuleRepository;

    private Cookie session;
    private User user;
    private final List<UUID> ruleIds = new ArrayList<>();

    // containsFood: CONTAINS, food, applied 5x
    // regexTravel: REGEX, travel, never applied
    // startsFood: STARTS_WITH, food (with a sub-category), applied 1x
    private CategoryRule containsFood;
    private CategoryRule regexTravel;
    private CategoryRule startsFood;

    @BeforeEach
    void setUp() throws Exception {
        String email = "rules-list-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        containsFood = rule("ZOMATO", MatchType.CONTAINS, "food", null, 5, Instant.parse("2026-10-01T00:00:00Z"));
        regexTravel = rule("IRCTC.*", MatchType.REGEX, "travel", null, 0, null);
        startsFood = rule("UBER", MatchType.STARTS_WITH, "food", "food.delivery", 1, Instant.parse("2026-09-01T00:00:00Z"));
    }

    /** Shared H2 across @SpringBootTest classes — remove every row this test created. */
    @AfterEach
    void tearDown() {
        categoryRuleRepository.deleteAllById(ruleIds);
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

    private CategoryRule rule(String key, MatchType matchType, String categoryKey, String subcategoryKey,
                              int appliedCount, Instant lastAppliedAt) {
        CategoryRule rule = new CategoryRule();
        rule.setUser(user);
        rule.setMerchantKey(key);
        rule.setMatchType(matchType);
        rule.setCategoryKey(categoryKey);
        rule.setSubcategoryKey(subcategoryKey);
        rule.setAppliedCount(appliedCount);
        rule.setLastAppliedAt(lastAppliedAt);
        CategoryRule saved = categoryRuleRepository.save(rule);
        ruleIds.add(saved.getId());
        return saved;
    }

    /** Rule ids in response order. */
    private List<UUID> list(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<UUID> ids = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(body).get("content")) {
            ids.add(UUID.fromString(node.get("id").asText()));
        }
        return ids;
    }

    private static MockHttpServletRequestBuilder rules() {
        return get("/api/v1/rules");
    }

    @Test
    void noFilters_returnsAllRules() throws Exception {
        assertEquals(Set.of(containsFood.getId(), regexTravel.getId(), startsFood.getId()), Set.copyOf(list(rules())));
    }

    @Test
    void matchTypeFilter() throws Exception {
        assertEquals(List.of(regexTravel.getId()), list(rules().param("matchType", "REGEX")));
        assertEquals(List.of(containsFood.getId()), list(rules().param("matchType", "contains")));
    }

    @Test
    void appliedFilter_splitsUsedFromNeverUsed() throws Exception {
        assertEquals(Set.of(containsFood.getId(), startsFood.getId()), Set.copyOf(list(rules().param("applied", "true"))));
        assertEquals(List.of(regexTravel.getId()), list(rules().param("applied", "false")));
    }

    @Test
    void categoryFilter_matchesRulesOfThatCategory() throws Exception {
        assertEquals(Set.of(containsFood.getId(), startsFood.getId()),
                Set.copyOf(list(rules().param("categoryKey", "food"))));
        assertEquals(List.of(regexTravel.getId()), list(rules().param("categoryKey", "travel")));
        assertEquals(List.of(), list(rules().param("categoryKey", "salary")));
    }

    @Test
    void response_carriesCategoryAndSubcategory() throws Exception {
        String body = mockMvc.perform(rules().param("matchType", "STARTS_WITH").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rule = objectMapper.readTree(body).get("content").get(0);
        assertEquals("food", rule.get("category").get("key").asText());
        assertEquals("Food", rule.get("category").get("name").asText());
        assertEquals("food.delivery", rule.get("subcategory").get("key").asText());
        assertTrue(rule.get("subcategory").get("name").asText().length() > 0);
    }

    @Test
    void filtersCombine() throws Exception {
        assertEquals(List.of(startsFood.getId()), list(rules()
                .param("matchType", "STARTS_WITH")
                .param("applied", "true")
                .param("categoryKey", "food")));
        assertEquals(List.of(), list(rules().param("matchType", "REGEX").param("applied", "true")));
    }

    // search isn't covered here: H2's Oracle mode reads Hibernate's `escape ''` as ESCAPE NULL, so
    // LIKE never matches in this database (Oracle's dialect doesn't emit the clause).
    @Test
    void sortByAppliedCountDesc() throws Exception {
        assertEquals(List.of(containsFood.getId(), startsFood.getId(), regexTravel.getId()),
                list(rules().param("sort", "appliedCount,desc")));
    }

    @Test
    void sortByLastAppliedAt_keepsNeverAppliedLastInBothDirections() throws Exception {
        assertEquals(List.of(containsFood.getId(), startsFood.getId(), regexTravel.getId()),
                list(rules().param("sort", "lastAppliedAt,desc")));
        assertEquals(List.of(startsFood.getId(), containsFood.getId(), regexTravel.getId()),
                list(rules().param("sort", "lastAppliedAt,asc")));
    }

    @Test
    void sortByMerchantKeyAsc() throws Exception {
        assertEquals(List.of(regexTravel.getId(), startsFood.getId(), containsFood.getId()),
                list(rules().param("sort", "merchantKey,asc")));
    }

    @Test
    void unsupportedSortField_is400() throws Exception {
        mockMvc.perform(rules().param("sort", "user,asc").cookie(session)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownMatchType_is400() throws Exception {
        mockMvc.perform(rules().param("matchType", "MERCHANT_KEY").cookie(session)).andExpect(status().isBadRequest());
    }
}
