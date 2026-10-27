package com.financeos.domain.epf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Two signed-up users, a fixed business date and request builders for the EPF endpoints. */
abstract class EpfIntegrationBase {

    static final String BASE = "/api/v1/epf";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired EpfEmployerRepository employerRepository;
    @Autowired EpfEntryRepository entryRepository;

    ApiTestClient api;
    ApiTestClient otherApi;
    UUID userId;
    UUID otherUserId;
    private UUID bankId;

    @BeforeEach
    void signUpUsers() throws Exception {
        String email = "epf-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "epf-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
    }

    @AfterEach
    void cleanUp() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    // ------------------------------------------------------------------ builders

    UUID employer(ApiTestClient client, String name, String memberId, String joinedOn, String leftOn) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("memberId", memberId);
        body.put("joinedOn", joinedOn);
        body.put("leftOn", leftOn);
        return client.create(BASE + "/employers", body);
    }

    UUID employer(String name, String joinedOn, String leftOn) throws Exception {
        return employer(api, name, null, joinedOn, leftOn);
    }

    static Map<String, Object> body(String type, UUID employerId, String creditedOn, Number employee, Number employer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("employerId", employerId.toString());
        body.put("creditedOn", creditedOn);
        body.put("employeeAmount", employee);
        body.put("employerAmount", employer);
        return body;
    }

    static Map<String, Object> contribution(UUID employerId, String wageMonth, String creditedOn, Number employee,
                                            Number employer, Number pension) {
        Map<String, Object> body = body("CONTRIBUTION", employerId, creditedOn, employee, employer);
        body.put("wageMonth", wageMonth);
        body.put("pensionAmount", pension);
        return body;
    }

    static Map<String, Object> interest(UUID employerId, int fy, String creditedOn, Number employee, Number employer) {
        Map<String, Object> body = body("INTEREST", employerId, creditedOn, employee, employer);
        body.put("interestFy", fy);
        return body;
    }

    static Map<String, Object> withdrawal(UUID employerId, String kind, String creditedOn, Number employee,
                                          Number employer, Number pension, Number tds) {
        Map<String, Object> body = body("WITHDRAWAL", employerId, creditedOn, employee, employer);
        body.put("withdrawalKind", kind);
        body.put("pensionAmount", pension);
        body.put("tdsAmount", tds);
        return body;
    }

    JsonNode addEntry(Map<String, Object> body) throws Exception {
        return api.postJson(BASE + "/entries", body, 201);
    }

    UUID addEntryId(Map<String, Object> body) throws Exception {
        return UUID.fromString(addEntry(body).get("id").asText());
    }

    /** An EPF row written straight to the database as if it came from a passbook upload. */
    UUID passbookEntry(UUID employerId, EpfEntryType type, String creditedOn, int employee, int employer) {
        EpfEntry e = new EpfEntry();
        e.setUser(userRepository.getReferenceById(userId));
        e.setEmployer(employerRepository.getReferenceById(employerId));
        e.setType(type);
        e.setSource(EpfEntrySource.PASSBOOK);
        e.setCreditedOn(LocalDate.parse(creditedOn));
        e.setEmployeeAmount(BigDecimal.valueOf(employee));
        e.setEmployerAmount(BigDecimal.valueOf(employer));
        if (type == EpfEntryType.CONTRIBUTION) {
            e.setWageMonth(LocalDate.parse(creditedOn).withDayOfMonth(1));
        }
        if (type == EpfEntryType.WITHDRAWAL) {
            e.setWithdrawalKind(EpfWithdrawalKind.PARTIAL);
        }
        return entryRepository.saveAndFlush(e).getId();
    }

    UUID bank() throws Exception {
        if (bankId == null) {
            bankId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "EPF Bank", "last4", "1234",
                    "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        }
        return bankId;
    }

    /** A positive amount is a credit (money in), a negative one a debit. */
    UUID transaction(Number amount, String date, String description) throws Exception {
        return api.create("/api/v1/transactions", Map.of("accountId", bank().toString(), "amount", amount, "date", date,
                "description", description));
    }

    // ------------------------------------------------------------------ assertions

    static void assertAmount(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), "expected " + expected + " but was " + actual);
    }

    static String message(JsonNode error) {
        return error.get("message").asText();
    }
}
