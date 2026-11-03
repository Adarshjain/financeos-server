package com.financeos.gmail.accountdetect.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Detector;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
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
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.financeos.gmail.accountdetect.DetectionFixtures.detection;
import static com.financeos.gmail.accountdetect.DetectionFixtures.entity;
import static org.assertj.core.api.Assertions.assertThat;

/** The exact limit and the unlock rule reach the suggestion row and the API; the rule is never a password. Made-up data. */
@SpringBootTest
@AutoConfigureMockMvc
class SuggestionUnlockIntegrationTest {

    private static final String BASE = "/api/v1/account-suggestions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountSuggestionService service;
    @Autowired private AccountSuggestionRepository repository;

    private ApiTestClient api;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "unlock-sugg-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private AccountSuggestion row(String key) {
        return repository.findByUserId(userId).stream().filter(s -> s.getKey().equals(key)).findFirst().orElseThrow();
    }

    private static StatementContent unlockedStatement(String bank, String last4, String rule) {
        return new StatementContent("PDF_MAIL", bank, last4, Kind.CREDIT_CARD, YearMonth.of(2026, 9), null, "3-5L", List.of(), false,
                LocalDate.of(2026, 9, 15), "cluster1", LocalDate.of(2026, 9, 16), null, new BigDecimal("450000.00"), rule);
    }

    private static Detection withLink(Detection d, String outcome, StatementContent s) {
        d.statementLinks.add(new Detection.StatementLink(s.source(), outcome, s.bank(), s.last4(), s.period(), s));
        return d;
    }

    @Test
    void theExactLimitIsStoredAndReturnedWhenTheLimitIsMediumOrBetter() throws Exception {
        Detection.Entity high = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        high.limitBucket = "3-5L";
        high.limitConf = Conf.HIGH;
        high.limitAmount = new BigDecimal("450000.00");
        Detection.Entity medium = entity("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH);
        medium.limitBucket = "1-3L";
        medium.limitConf = Conf.MEDIUM;
        medium.limitAmount = new BigDecimal("125000.50");
        Detection.Entity low = entity("axis", "4444", Kind.CREDIT_CARD, Conf.HIGH);
        low.limitBucket = "1-3L";
        low.limitConf = Conf.LOW;
        low.limitAmount = new BigDecimal("150000.00");   // never reported below MEDIUM, even if a caller left it set
        Detection.Entity none = entity("sbi", "5555", Kind.CREDIT_CARD, Conf.HIGH);

        service.apply(userId, detection(high, medium, low, none));

        assertThat(row("hdfc:CREDIT_CARD:2222").getLimitAmount()).isEqualByComparingTo("450000.00");
        assertThat(row("icici:CREDIT_CARD:3333").getLimitAmount()).isEqualByComparingTo("125000.50");
        assertThat(row("axis:CREDIT_CARD:4444").getLimitAmount()).isNull();
        assertThat(row("sbi:CREDIT_CARD:5555").getLimitAmount()).isNull();
        for (JsonNode item : api.getJson(BASE, 200).get("items")) {
            String last4 = item.get("last4").asText();
            switch (last4) {
                case "2222" -> assertThat(item.get("limitAmount").decimalValue()).isEqualByComparingTo("450000.00");
                case "3333" -> assertThat(item.get("limitAmount").decimalValue()).isEqualByComparingTo("125000.50");
                default -> assertThat(item.get("limitAmount").isNull()).isTrue();
            }
            assertThat(item.get("passwordFromProfile").asBoolean()).isFalse();
        }
    }

    @Test
    void theRuleThatOpenedAnEntitiesStatementsIsKeptAndOnlyAFlagIsReturned() throws Exception {
        Detection d = detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH), entity("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH));
        withLink(d, Detector.LINK_LAST4, unlockedStatement("hdfc", "2222", "NAME4U+CARD4"));
        withLink(d, Detector.LINK_ORIGIN, unlockedStatement("icici", "3333", "NAME4L+DDMM"));

        service.apply(userId, d);

        // the flag means "the rule renders now for this user's profile": it needs the name and, for DDMM, the birth date
        com.financeos.domain.user.User user = userRepository.findById(userId).orElseThrow();
        user.setDisplayName("Riya Sharma");
        user.setBirthDate("1994-08-14");
        userRepository.save(user);
        assertThat(row("hdfc:CREDIT_CARD:2222").getPasswordRule()).isEqualTo("NAME4U+CARD4");
        assertThat(row("icici:CREDIT_CARD:3333").getPasswordRule()).isEqualTo("NAME4L+DDMM");
        JsonNode items = api.getJson(BASE, 200).get("items");
        assertThat(items).hasSize(2);
        for (JsonNode item : items) {
            assertThat(item.get("passwordFromProfile").asBoolean()).isTrue();
            assertThat(item.has("passwordRule")).isFalse();
        }
        assertThat(items.toString()).doesNotContain("NAME4").doesNotContain("password\":");
    }

    @Test
    void passwordFromProfileIsFalseWhenTheRuleNeedsAProfileFieldTheUserLacks() throws Exception {
        Detection d = detection(entity("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH));
        withLink(d, Detector.LINK_ORIGIN, unlockedStatement("icici", "3333", "NAME4L+DDMM"));
        service.apply(userId, d);
        com.financeos.domain.user.User user = userRepository.findById(userId).orElseThrow();
        user.setDisplayName("Riya Sharma");
        user.setBirthDate(null);
        userRepository.save(user);

        assertThat(api.getJson(BASE, 200).get("items").get(0).get("passwordFromProfile").asBoolean()).isFalse();
        assertThat(row("icici:CREDIT_CARD:3333").getPasswordRule()).isEqualTo("NAME4L+DDMM");   // the rule itself is kept
    }

    @Test
    void savedPasswordsIdentifierRulesAndWeakLinksNeverBecomeARule() {
        Detection d = detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH), entity("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH),
                entity("axis", "4444", Kind.CREDIT_CARD, Conf.HIGH));
        withLink(d, Detector.LINK_LAST4, unlockedStatement("hdfc", "2222", "SAVED"));
        withLink(d, Detector.LINK_LAST4, unlockedStatement("icici", "3333", "CUSTID+DDMMYYYY"));
        withLink(d, Detector.LINK_PERIOD, unlockedStatement("axis", "4444", "NAME4U+DDMM"));   // linked only by period: not trusted

        service.apply(userId, d);

        assertThat(row("hdfc:CREDIT_CARD:2222").getPasswordRule()).isNull();
        assertThat(row("icici:CREDIT_CARD:3333").getPasswordRule()).isNull();
        assertThat(row("axis:CREDIT_CARD:4444").getPasswordRule()).isNull();
    }

    @Test
    void aLaterRunWithoutTheRuleClearsItOnANewRow() {
        Detection d = detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH));
        withLink(d, Detector.LINK_LAST4, unlockedStatement("hdfc", "2222", "NAME4U+DDMM"));
        service.apply(userId, d);
        assertThat(row("hdfc:CREDIT_CARD:2222").getPasswordRule()).isNotNull();

        service.apply(userId, detection(entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH)));
        assertThat(row("hdfc:CREDIT_CARD:2222").getPasswordRule()).isNull();
    }

    @Test
    void aNumberNotInTheUsersNameIsAMaybeWithACaptionAndAFlag() throws Exception {
        Detection.Entity mine = entity("hdfc", "2222", Kind.CREDIT_CARD, Conf.HIGH);
        Detection.Entity theirs = entity("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH);
        theirs.notInYourName = true;

        service.apply(userId, detection(mine, theirs));

        assertThat(row("hdfc:CREDIT_CARD:2222").isNotInYourName()).isFalse();
        assertThat(row("icici:CREDIT_CARD:3333").isNotInYourName()).isTrue();
        assertThat(row("icici:CREDIT_CARD:3333").getBand()).isEqualTo(AccountSuggestion.Band.MAYBE);
        assertThat(row("hdfc:CREDIT_CARD:2222").getBand()).isEqualTo(AccountSuggestion.Band.FOUND);
        for (JsonNode item : api.getJson(BASE, 200).get("items")) {
            boolean flagged = "3333".equals(item.get("last4").asText());
            assertThat(item.get("notInYourName").asBoolean()).isEqualTo(flagged);
            if (flagged) {
                assertThat(item.get("band").asText()).isEqualTo("MAYBE");
                assertThat(item.get("caption").asText()).startsWith("Not in your name");
            } else {
                assertThat(item.get("caption").asText()).doesNotContain("Not in your name");
            }
        }

        // a later run that no longer flags it clears the flag
        service.apply(userId, detection(mine, entity("icici", "3333", Kind.CREDIT_CARD, Conf.HIGH)));
        assertThat(row("icici:CREDIT_CARD:3333").isNotInYourName()).isFalse();
        assertThat(row("icici:CREDIT_CARD:3333").getBand()).isEqualTo(AccountSuggestion.Band.FOUND);
    }
}
