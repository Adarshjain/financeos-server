package com.financeos.domain.recurring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recurring series end to end on H2: detection, the API, tracking, auto-link, missed cycles, loans and tenancy. */
@SpringBootTest
@AutoConfigureMockMvc
class RecurringIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private RecurringService recurringService;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private UUID bank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "recurring-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "recurring-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank A", "last4", "1234",
                "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    // ------------------------------------------------------------------ helpers

    private UUID debit(ApiTestClient client, UUID account, int amount, int daysAgo, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", AppTime.today().minusDays(daysAgo).toString());
        body.put("amount", -amount);
        body.put("description", description);
        return client.create("/api/v1/transactions", body);
    }

    /** Three monthly payments, the latest 35 days ago, so the next one is due about 5 days ago. */
    private void threeMonthly(String description, int amount) throws Exception {
        debit(api, bank, amount, 95, description);
        debit(api, bank, amount, 65, description);
        debit(api, bank, amount, 35, description);
    }

    private void detect() {
        UserContextHelper.runAs(userId, () -> recurringService.detect(userId));
    }

    private void link() {
        UserContextHelper.runAs(userId, () -> recurringService.linkAndAdvance(userId));
    }

    private JsonNode proposals() throws Exception {
        return api.getJson("/api/v1/recurring/proposals", 200);
    }

    private String onlyProposalId() throws Exception {
        JsonNode list = proposals();
        assertEquals(1, list.size());
        return list.get(0).get("id").asText();
    }

    private static LocalDate day(Object value) {
        return value instanceof java.sql.Timestamp t ? t.toLocalDateTime().toLocalDate() : ((java.sql.Date) value).toLocalDate();
    }

    private Map<String, Object> seriesRow() {
        return jdbc.queryForMap("SELECT * FROM recurring_series WHERE user_id = ?", userId.toString());
    }

    private UUID createLoan(ApiTestClient client, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("loanType", "personal");
        body.put("lender", "Acme Lender");
        body.put("principal", "120000");
        body.put("annualRatePct", "9.5");
        body.put("rateType", "fixed");
        body.put("tenureMonths", 24);
        body.put("startDate", AppTime.today().minusDays(20).toString());
        body.put("firstEmiDate", AppTime.today().plusDays(10).toString());
        return client.create("/api/v1/loans", body);
    }

    // ------------------------------------------------------------------ detection and proposals

    @Test
    void threeMonthlyPaymentsBecomeAProposalWithItsShape() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB 556677", 1200);
        detect();

        JsonNode p = proposals().get(0);
        assertEquals("OTHER", p.get("kind").asText());
        assertEquals("Zorblax Gym Club", p.get("displayName").asText());
        assertEquals("MONTHLY", p.get("period").asText());
        assertEquals(1200, p.get("amount").asInt());
        assertEquals(AppTime.today().minusDays(35).toString(), p.get("lastPaidOn").asText());
        assertEquals(3, p.get("paymentCount").asInt());
        assertEquals("Bank A", p.get("accountName").asText());
        assertEquals("PROPOSED", p.get("status").asText());
        assertTrue(p.get("loanPrefill").isNull());
    }

    @Test
    void twoPaymentsGiveNoProposal() throws Exception {
        debit(api, bank, 300, 65, "ZORBLAX GYM CLUB");
        debit(api, bank, 300, 35, "ZORBLAX GYM CLUB");
        detect();
        assertEquals(0, proposals().size());
    }

    @Test
    void anExcludedPaymentDoesNotCount() throws Exception {
        UUID oldest = debit(api, bank, 300, 95, "ZORBLAX GYM CLUB");
        debit(api, bank, 300, 65, "ZORBLAX GYM CLUB");
        debit(api, bank, 300, 35, "ZORBLAX GYM CLUB");
        jdbc.update("UPDATE transactions SET is_excluded = TRUE WHERE id = ?", oldest.toString());
        detect();
        assertEquals(0, proposals().size());
    }

    @Test
    void redetectionUpdatesTheProposalInPlace() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1200);
        detect();
        String id = onlyProposalId();

        debit(api, bank, 1250, 5, "ZORBLAX GYM CLUB");
        detect();

        JsonNode p = proposals().get(0);
        assertEquals(1, proposals().size());
        assertEquals(id, p.get("id").asText());
        assertEquals(4, p.get("paymentCount").asInt());
        assertEquals(AppTime.today().minusDays(5).toString(), p.get("lastPaidOn").asText());
    }

    @Test
    void anEmiLookingSeriesCarriesALoanPrefill() throws Exception {
        threeMonthly("BAJAJ FINANCE EMI 445566", 4500);
        detect();

        JsonNode p = proposals().get(0);
        assertEquals("EMI", p.get("kind").asText());
        JsonNode prefill = p.get("loanPrefill");
        assertEquals("Bajaj Finance Emi", prefill.get("name").asText());
        assertEquals(4500, prefill.get("emiAmount").asInt());
        assertEquals(AppTime.today().minusDays(35).getDayOfMonth(), prefill.get("emiDayOfMonth").asInt());
        assertEquals(AppTime.today().minusDays(95).toString(), prefill.get("firstPaymentGuess").asText());
        assertEquals(bank.toString(), prefill.get("accountId").asText());
    }

    @Test
    void kindsAreSubscriptionEmiAndOther() throws Exception {
        threeMonthly("UPI NETFLIX 998877", 649);
        threeMonthly("ZORBLAX HOME LOAN ACH", 9000);
        threeMonthly("ZORBLAX GYM CLUB", 1200);
        detect();

        Map<String, String> kinds = new LinkedHashMap<>();
        proposals().forEach(p -> kinds.put(p.get("displayName").asText(), p.get("kind").asText()));
        assertEquals("SUBSCRIPTION", kinds.get("Netflix"));
        assertEquals("EMI", kinds.get("Zorblax Home Loan"));
        assertEquals("OTHER", kinds.get("Zorblax Gym Club"));
    }

    @Test
    void aDismissedSeriesIsNeverProposedAgain() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1200);
        detect();
        String id = onlyProposalId();

        api.postJson("/api/v1/recurring/" + id + "/dismiss", Map.of(), 200);
        debit(api, bank, 1200, 5, "ZORBLAX GYM CLUB");
        detect();

        assertEquals(0, proposals().size());
        assertEquals("DISMISSED", seriesRow().get("status"));
    }

    @Test
    void anEndedSeriesComesBackOnlyFromPaymentsAfterItEnded() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1200);
        detect();
        String id = onlyProposalId();
        api.postJson("/api/v1/recurring/" + id + "/track", Map.of(), 200);
        api.postJson("/api/v1/recurring/" + id + "/stop", Map.of(), 200);
        assertEquals("ENDED", seriesRow().get("status"));

        detect();
        assertEquals(0, proposals().size(), "the same old payments must not bring it back");

        // The series ended long before these three payments.
        jdbc.update("UPDATE recurring_series SET last_paid_on = ? WHERE user_id = ?",
                java.sql.Date.valueOf(AppTime.today().minusDays(200)), userId.toString());
        detect();
        assertEquals(1, proposals().size());
        assertEquals("PROPOSED", seriesRow().get("status"));
    }

    // ------------------------------------------------------------------ tracking

    @Test
    void aTrackedSeriesShowsInUpcomingAsARecurringRow() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1200);
        detect();
        String id = onlyProposalId();

        JsonNode tracked = api.postJson("/api/v1/recurring/" + id + "/track", Map.of(), 200);
        assertEquals("TRACKED", tracked.get("status").asText());
        assertEquals(0, proposals().size());
        assertEquals(1, api.getJson("/api/v1/recurring/tracked", 200).size());

        JsonNode items = api.getJson("/api/v1/obligations/upcoming?kinds=recurring", 200).get("items");
        assertEquals(1, items.size());
        JsonNode row = items.get(0);
        assertEquals("recurring", row.get("type").asText());
        assertEquals("Zorblax Gym Club", row.get("title").asText());
        assertEquals(1200, row.get("amount").asInt());
        assertEquals(id, row.get("seriesId").asText());
        assertFalse(row.get("paid").asBoolean());
        assertEquals("Bank A", row.get("accountName").asText());
        assertFalse(row.get("date").isNull());

        // The endpoint's default list carries them too; asking for other kinds only does not.
        JsonNode defaults = api.getJson("/api/v1/obligations/upcoming", 200).get("items");
        assertEquals(1, java.util.stream.StreamSupport.stream(defaults.spliterator(), false)
                .filter(i -> "recurring".equals(i.get("type").asText())).count());
        assertEquals(0, api.getJson("/api/v1/obligations/upcoming?kinds=emi", 200).get("items").size());
    }

    @Test
    void aNewPaymentLinksAndAPriceChangeUpdatesTheAmount() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        api.postJson("/api/v1/recurring/" + onlyProposalId() + "/track", Map.of(), 200);
        LocalDate dueBefore = day(seriesRow().get("next_due_on"));

        UUID txn = debit(api, bank, 1100, 3, "ZORBLAX GYM CLUB");
        link();

        Map<String, Object> row = seriesRow();
        assertEquals(0, new java.math.BigDecimal("1100").compareTo((java.math.BigDecimal) row.get("amount")));
        assertEquals(AppTime.today().minusDays(3), day(row.get("last_paid_on")));
        assertEquals(AppTime.today().minusDays(3).plusMonths(1), day(row.get("next_due_on")));
        assertTrue(day(row.get("next_due_on")).isAfter(dueBefore));
        assertEquals(0, ((Number) row.get("missed_cycles")).intValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_series_payments WHERE transaction_id = ?",
                Integer.class, txn.toString()));

        // Running again links nothing twice.
        link();
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_series_payments", Integer.class));
    }

    @Test
    void aPaymentFarFromTheAmountIsNotLinked() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        api.postJson("/api/v1/recurring/" + onlyProposalId() + "/track", Map.of(), 200);

        debit(api, bank, 1400, 3, "ZORBLAX GYM CLUB");
        link();

        assertEquals(0, new java.math.BigDecimal("1000").compareTo((java.math.BigDecimal) seriesRow().get("amount")));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_series_payments", Integer.class));
    }

    @Test
    void aLatePaymentStillLinksToTheOverdueCycle() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        api.postJson("/api/v1/recurring/" + onlyProposalId() + "/track", Map.of(), 200);
        // Due 12 days ago (window 5); the payment is 8 days late, well before the next window opens.
        jdbc.update("UPDATE recurring_series SET next_due_on = ? WHERE user_id = ?",
                java.sql.Date.valueOf(AppTime.today().minusDays(12)), userId.toString());
        debit(api, bank, 1000, 4, "ZORBLAX GYM CLUB");
        link();

        Map<String, Object> row = seriesRow();
        assertEquals("TRACKED", row.get("status"));
        assertEquals(0, ((Number) row.get("missed_cycles")).intValue());
        assertEquals(AppTime.today().minusDays(4), day(row.get("last_paid_on")));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM recurring_series_payments", Integer.class));
    }

    @Test
    void oneMissedCycleKeepsItTrackedAndTwoEndIt() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        api.postJson("/api/v1/recurring/" + onlyProposalId() + "/track", Map.of(), 200);
        // The account's data is current: something else was spent today.
        debit(api, bank, 50, 0, "TEA STALL");

        // Due 10 days ago: the next window has not opened, so nothing is missed yet.
        jdbc.update("UPDATE recurring_series SET next_due_on = ? WHERE user_id = ?",
                java.sql.Date.valueOf(AppTime.today().minusDays(10)), userId.toString());
        link();
        assertEquals(0, ((Number) seriesRow().get("missed_cycles")).intValue());

        // Due 30 days ago: the next window has opened with nothing paid, one cycle missed.
        jdbc.update("UPDATE recurring_series SET next_due_on = ? WHERE user_id = ?",
                java.sql.Date.valueOf(AppTime.today().minusDays(30)), userId.toString());
        link();
        assertEquals("TRACKED", seriesRow().get("status"));
        assertEquals(1, ((Number) seriesRow().get("missed_cycles")).intValue());

        // Due 60 days ago: both cycles since then passed with nothing paid.
        jdbc.update("UPDATE recurring_series SET next_due_on = ?, missed_cycles = 0 WHERE user_id = ?",
                java.sql.Date.valueOf(AppTime.today().minusDays(60)), userId.toString());
        link();
        assertEquals("ENDED", seriesRow().get("status"));
        assertEquals(2, ((Number) seriesRow().get("missed_cycles")).intValue());
        assertEquals(0, api.getJson("/api/v1/obligations/upcoming?kinds=recurring", 200).get("items").size());
    }

    @Test
    void aSeriesIsNotCountedMissedWhileTheAccountHasNoNewerData() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        api.postJson("/api/v1/recurring/" + onlyProposalId() + "/track", Map.of(), 200);

        // Due 30 days ago (its window ended 25 days ago), but the newest transaction on the account is 35 days old: the statement is missing.
        jdbc.update("UPDATE recurring_series SET next_due_on = ? WHERE user_id = ?",
                java.sql.Date.valueOf(AppTime.today().minusDays(30)), userId.toString());
        link();

        Map<String, Object> row = seriesRow();
        assertEquals("TRACKED", row.get("status"));
        assertEquals(0, ((Number) row.get("missed_cycles")).intValue());
        assertEquals(AppTime.today().minusDays(30), day(row.get("next_due_on")));
    }

    @Test
    void stoppingATrackedSeriesEndsIt() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        String id = onlyProposalId();
        api.postJson("/api/v1/recurring/" + id + "/track", Map.of(), 200);

        JsonNode stopped = api.postJson("/api/v1/recurring/" + id + "/stop", Map.of(), 200);
        assertEquals("ENDED", stopped.get("status").asText());
        assertEquals(0, api.getJson("/api/v1/recurring/tracked", 200).size());
    }

    @Test
    void anActionOnTheWrongStatusIsRejected() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        String id = onlyProposalId();
        api.postJson("/api/v1/recurring/" + id + "/stop", Map.of(), 400);
        api.postJson("/api/v1/recurring/" + id + "/track", Map.of(), 200);
        api.postJson("/api/v1/recurring/" + id + "/track", Map.of(), 400);
        api.postJson("/api/v1/recurring/" + id + "/dismiss", Map.of(), 400);
    }

    // ------------------------------------------------------------------ loans

    @Test
    void convertingAnEmiProposalMarksItAsALoan() throws Exception {
        threeMonthly("BAJAJ FINANCE EMI 445566", 4500);
        detect();
        String id = onlyProposalId();
        UUID loanId = createLoan(api, "Bajaj loan");

        JsonNode done = api.postJson("/api/v1/recurring/" + id + "/converted", Map.of("loanId", loanId.toString()), 200);
        assertEquals("LOAN", done.get("status").asText());
        assertEquals(loanId.toString(), seriesRow().get("loan_id"));

        detect();
        assertEquals(0, proposals().size(), "a converted series is never proposed again");
        assertEquals("LOAN", seriesRow().get("status"));
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    void anotherUsersSeriesIsRejectedOnEveryAction() throws Exception {
        threeMonthly("ZORBLAX GYM CLUB", 1000);
        detect();
        String id = onlyProposalId();
        UUID otherLoan = createLoan(otherApi, "Other loan");
        UUID myLoan = createLoan(api, "My loan");

        otherApi.postJson("/api/v1/recurring/" + id + "/track", Map.of(), 404);
        otherApi.postJson("/api/v1/recurring/" + id + "/dismiss", Map.of(), 404);
        otherApi.postJson("/api/v1/recurring/" + id + "/stop", Map.of(), 404);
        otherApi.postJson("/api/v1/recurring/" + id + "/converted", Map.of("loanId", otherLoan.toString()), 404);
        assertEquals(0, otherApi.getJson("/api/v1/recurring/proposals", 200).size());
        assertEquals("PROPOSED", seriesRow().get("status"));

        // The owner cannot attach someone else's loan either.
        api.postJson("/api/v1/recurring/" + id + "/converted", Map.of("loanId", otherLoan.toString()), 404);
        assertEquals("PROPOSED", seriesRow().get("status"));
        assertNull(seriesRow().get("loan_id"));
        api.postJson("/api/v1/recurring/" + id + "/converted", Map.of("loanId", myLoan.toString()), 200);
    }
}
