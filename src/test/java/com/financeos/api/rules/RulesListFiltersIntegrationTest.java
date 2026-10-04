package com.financeos.api.rules;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
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
 * GET /rules filters (source, matchType, applied, categoryId) and the sort whitelist, run through
 * the real JPQL so a broken clause fails here rather than only on Oracle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RulesListFiltersIntegrationTest {

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

    // llmFood: LLM, MERCHANT_KEY, food, applied 5x
    // userTravel: USER, REGEX, travel, never applied
    // userBoth: USER, CONTAINS, food+travel, applied 1x
    private CategoryRule llmFood;
    private CategoryRule userTravel;
    private CategoryRule userBoth;

    @BeforeEach
    void setUp() throws Exception {
        String email = "rules-list-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        food = categoryRepository.save(new Category("Food", user));
        travel = categoryRepository.save(new Category("Travel", user));

        llmFood = rule("ZOMATO", MatchType.MERCHANT_KEY, "LLM", 5, Instant.parse("2026-10-01T00:00:00Z"), food);
        userTravel = rule("IRCTC.*", MatchType.REGEX, "USER", 0, null, travel);
        userBoth = rule("UBER", MatchType.CONTAINS, "USER", 1, Instant.parse("2026-09-01T00:00:00Z"), food, travel);
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

    private CategoryRule rule(String key, MatchType matchType, String source, int appliedCount,
                              Instant lastAppliedAt, Category... categories) {
        CategoryRule rule = new CategoryRule();
        rule.setUser(user);
        rule.setMerchantKey(key);
        rule.setMatchType(matchType);
        rule.setSource(source);
        rule.setVerified(false);
        rule.setAppliedCount(appliedCount);
        rule.setLastAppliedAt(lastAppliedAt);
        rule.setCategories(new HashSet<>(Set.of(categories)));
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
        assertEquals(Set.of(llmFood.getId(), userTravel.getId(), userBoth.getId()), Set.copyOf(list(rules())));
    }

    @Test
    void sourceFilter_isCaseInsensitive() throws Exception {
        assertEquals(List.of(llmFood.getId()), list(rules().param("source", "llm")));
        assertEquals(Set.of(userTravel.getId(), userBoth.getId()), Set.copyOf(list(rules().param("source", "USER"))));
    }

    @Test
    void matchTypeFilter() throws Exception {
        assertEquals(List.of(userTravel.getId()), list(rules().param("matchType", "REGEX")));
        assertEquals(List.of(llmFood.getId()), list(rules().param("matchType", "MERCHANT_KEY")));
    }

    @Test
    void appliedFilter_splitsUsedFromNeverUsed() throws Exception {
        assertEquals(Set.of(llmFood.getId(), userBoth.getId()), Set.copyOf(list(rules().param("applied", "true"))));
        assertEquals(List.of(userTravel.getId()), list(rules().param("applied", "false")));
    }

    @Test
    void categoryFilter_matchesRulesHavingThatCategoryAmongOthers() throws Exception {
        assertEquals(Set.of(llmFood.getId(), userBoth.getId()),
                Set.copyOf(list(rules().param("categoryId", food.getId().toString()))));
        assertEquals(Set.of(userTravel.getId(), userBoth.getId()),
                Set.copyOf(list(rules().param("categoryId", travel.getId().toString()))));
    }

    @Test
    void categoryFilter_keepsAllCategoriesOnMatchedRules() throws Exception {
        String body = mockMvc.perform(rules().param("categoryId", food.getId().toString())
                        .param("matchType", "CONTAINS").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode categories = objectMapper.readTree(body).get("content").get(0).get("categories");
        assertEquals(2, categories.size(), "filtering by one category must not trim the rule's other categories");
    }

    @Test
    void filtersCombine() throws Exception {
        assertEquals(List.of(userBoth.getId()), list(rules()
                .param("source", "USER")
                .param("applied", "true")
                .param("categoryId", food.getId().toString())));
        assertEquals(List.of(), list(rules().param("source", "LLM").param("applied", "false")));
    }

    // search isn't covered here: H2's Oracle mode reads Hibernate's `escape ''` as ESCAPE NULL, so
    // LIKE never matches in this database (Oracle's dialect doesn't emit the clause).
    @Test
    void filtersCombineWithVerified() throws Exception {
        assertEquals(List.of(), list(rules().param("verified", "true").param("source", "USER")));
        assertEquals(List.of(userTravel.getId()), list(rules().param("verified", "false").param("source", "USER")
                .param("applied", "false")));
    }

    @Test
    void sortByAppliedCountDesc() throws Exception {
        assertEquals(List.of(llmFood.getId(), userBoth.getId(), userTravel.getId()),
                list(rules().param("sort", "appliedCount,desc")));
    }

    @Test
    void sortByLastAppliedAt_keepsNeverAppliedLastInBothDirections() throws Exception {
        assertEquals(List.of(llmFood.getId(), userBoth.getId(), userTravel.getId()),
                list(rules().param("sort", "lastAppliedAt,desc")));
        assertEquals(List.of(userBoth.getId(), llmFood.getId(), userTravel.getId()),
                list(rules().param("sort", "lastAppliedAt,asc")));
    }

    @Test
    void sortByMerchantKeyAsc() throws Exception {
        assertEquals(List.of(userTravel.getId(), userBoth.getId(), llmFood.getId()),
                list(rules().param("sort", "merchantKey,asc")));
    }

    @Test
    void unsupportedSortField_is400() throws Exception {
        mockMvc.perform(rules().param("sort", "user,asc").cookie(session)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownSource_is400() throws Exception {
        mockMvc.perform(rules().param("source", "SYSTEM").cookie(session)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownMatchType_is400() throws Exception {
        mockMvc.perform(rules().param("matchType", "FUZZY").cookie(session)).andExpect(status().isBadRequest());
    }
}
