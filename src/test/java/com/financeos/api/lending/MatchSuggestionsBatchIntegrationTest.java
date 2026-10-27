package com.financeos.api.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepositoryCustom.MatchWindow;
import com.financeos.domain.transaction.TransactionType;
import org.hibernate.Session;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Batched match suggestions (all people / all loans): identical to the per-person / per-loan views,
 * exact at the tolerance and window edges, and a query count that does not grow with the number of people or loans.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MatchSuggestionsBatchIntegrationTest {

    private static final List<String> NAMES = List.of("Asha", "Bhanu", "Chetan", "Divya", "Esha", "Farah", "Gopal",
            "Hema", "Irfan", "Jaya", "Kiran", "Lata", "Mohan", "Nisha", "Omar", "Pooja");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JobWorker jobWorker;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    private ApiTestClient api;
    private ApiTestClient other;
    private UUID userId;
    private UUID otherId;
    private UUID bank;
    private UUID otherBank;
    private final LocalDate today = AppTime.today();

    @BeforeEach
    void setUp() throws Exception {
        String email = "match-batch-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "match-batch-o-" + UUID.randomUUID() + "@example.test";
        other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        bank = account(api, "Batch Bank", "1111");
        otherBank = account(other, "Other Bank", "2222");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private static UUID account(ApiTestClient client, String name, String last4) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", last4,
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID debit(ApiTestClient client, UUID account, String amount, LocalDate date, String description) throws Exception {
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("accountId", account.toString());
        txn.put("date", date.toString());
        txn.put("amount", new BigDecimal(amount).negate());
        txn.put("description", description);
        return client.create("/api/v1/transactions", txn);
    }

    private UUID person(String name) throws Exception {
        return api.create("/api/v1/counterparties", Map.of("name", name));
    }

    private void entry(UUID cp, String direction, String kind, String amount, LocalDate date, UUID txn) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("counterpartyId", cp.toString());
        body.put("direction", direction);
        body.put("kind", kind);
        body.put("amount", amount);
        body.put("entryDate", date.toString());
        if (txn != null) body.put("transactionId", txn.toString());
        api.create("/api/v1/lendings", body);
    }

    private long queriesFor(String path) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        // statistics are global: keep the job worker's poll out of the count
        jobWorker.pause();
        try {
            stats.clear();
            api.getJson(path, 200);
            return stats.getPrepareStatementCount();
        } finally {
            jobWorker.resume();
        }
    }

    private static Set<String> candidateIds(JsonNode suggestions) {
        Set<String> ids = new TreeSet<>();
        suggestions.forEach(s -> s.get("candidates").forEach(c -> ids.add(c.get("id").asText())));
        return ids;
    }

    // --- lendings ---------------------------------------------------------------------------------

    @Test
    void lendingBatchEqualsPerPersonAtToleranceAndWindowEdges() throws Exception {
        LocalDate d = today.minusDays(30);
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            String name = NAMES.get(i);
            int amt = 1000 + 100 * i;
            UUID cp = person(name);
            entry(cp, "lent", "principal", String.valueOf(amt), d, null);
            Set<String> in = new TreeSet<>();
            in.add(debit(api, bank, amt + ".00", d, "exact " + name).toString());
            in.add(debit(api, bank, (amt + 20) + ".00", d.plusDays(7), "upper edge " + name).toString());
            in.add(debit(api, bank, (amt - 20) + ".00", d.minusDays(7), "lower edge " + name).toString());
            debit(api, bank, (amt + 20) + ".01", d, "just over amount " + name);
            debit(api, bank, amt + ".00", d.plusDays(8), "just outside window " + name);
            debit(other, otherBank, amt + ".00", d, "foreign " + name);
            expected.put(name, in);
        }
        // a transaction another entry already references is never offered
        UUID referenced = debit(api, bank, "1000.00", d, "referenced");
        entry(person("Zed"), "lent", "principal", "1000", d, referenced);

        JsonNode all = api.getJson("/api/v1/counterparties/match-suggestions", 200);
        assertEquals(4, all.size());
        for (JsonNode row : all) {
            String name = row.get("counterpartyName").asText();
            assertEquals(expected.get(name), candidateIds(row.get("suggestions")), name);
            assertFalse(candidateIds(row.get("suggestions")).contains(referenced.toString()), name);
            JsonNode single = api.getJson("/api/v1/counterparties/" + row.get("counterpartyId").asText() + "/match-suggestions", 200);
            assertEquals(single.get("suggestions"), row.get("suggestions"), name);
        }
    }

    @Test
    void lendingQueryCountDoesNotGrowWithPeople() throws Exception {
        LocalDate d = today.minusDays(30);
        for (int i = 0; i < 3; i++) {
            lendingPerson(i, d);
        }
        for (String path : List.of("/api/v1/counterparties/match-suggestions", "/api/v1/counterparties/unrecorded-transfers")) {
            queriesFor(path); // warm up
        }
        long matchSmall = queriesFor("/api/v1/counterparties/match-suggestions");
        long unrecordedSmall = queriesFor("/api/v1/counterparties/unrecorded-transfers");
        for (int i = 3; i < NAMES.size(); i++) {
            lendingPerson(i, d);
        }
        long matchLarge = queriesFor("/api/v1/counterparties/match-suggestions");
        long unrecordedLarge = queriesFor("/api/v1/counterparties/unrecorded-transfers");
        assertEquals(matchSmall, matchLarge, "match-suggestions grew with the number of people");
        assertEquals(unrecordedSmall, unrecordedLarge, "unrecorded-transfers grew with the number of people");
        assertTrue(matchLarge <= 9, "match-suggestions used " + matchLarge + " queries");
        assertTrue(unrecordedLarge <= 12, "unrecorded-transfers used " + unrecordedLarge + " queries");
        assertEquals(NAMES.size(), api.getJson("/api/v1/counterparties/match-suggestions", 200).size());
    }

    private void lendingPerson(int i, LocalDate d) throws Exception {
        String name = NAMES.get(i);
        UUID cp = person(name);
        int amt = 1000 + 100 * i;
        entry(cp, "lent", "principal", String.valueOf(amt), d, null);
        entry(cp, "borrowed", "principal", "5000", d.minusDays(60), null);
        debit(api, bank, amt + ".00", d.plusDays(2), "pay " + name + " back");
        debit(api, bank, "777.00", today.minusDays(3), "Transfer to " + name);
    }

    // --- loans ------------------------------------------------------------------------------------

    private UUID loan(String name, String principal, UUID paymentAccount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("loanType", "personal");
        body.put("lender", "Bank");
        body.put("principal", principal);
        body.put("annualRatePct", "12");
        body.put("rateType", "fixed");
        body.put("tenureMonths", 12);
        body.put("startDate", today.minusDays(60).toString());
        body.put("firstEmiDate", today.minusDays(35).toString());
        if (paymentAccount != null) body.put("paymentAccountId", paymentAccount.toString());
        return api.create("/api/v1/loans", body);
    }

    private JsonNode firstInstallment(UUID loanId) throws Exception {
        return api.getJson("/api/v1/loans/" + loanId + "/schedule", 200).get("installments").get(0);
    }

    @Test
    void loanBatchEqualsPerLoanAtToleranceAndWindowEdges() throws Exception {
        UUID second = account(api, "Second Bank", "3333");
        UUID a = loan("Loan A", "12000", null);
        UUID b = loan("Loan B", "60000", bank);
        UUID c = loan("Loan C", "30000", null);
        Map<UUID, Set<String>> expected = new LinkedHashMap<>();
        for (UUID id : List.of(a, b, c)) {
            JsonNode inst = firstInstallment(id);
            BigDecimal emi = new BigDecimal(inst.get("emi").asText()).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal tol = emi.multiply(new BigDecimal("0.01")).max(new BigDecimal("100"));
            LocalDate due = LocalDate.parse(inst.get("dueDate").asText());
            UUID acct = id.equals(b) ? bank : bank;
            Set<String> in = new TreeSet<>();
            in.add(debit(api, acct, emi.toPlainString(), due, "exact").toString());
            in.add(debit(api, acct, emi.add(tol).toPlainString(), due.plusDays(7), "upper edge").toString());
            in.add(debit(api, acct, emi.subtract(tol).toPlainString(), due.minusDays(7), "lower edge").toString());
            debit(api, acct, emi.add(tol).add(new BigDecimal("0.01")).toPlainString(), due, "just over amount");
            debit(api, acct, emi.toPlainString(), due.plusDays(8), "just outside window");
            debit(other, otherBank, emi.toPlainString(), due, "foreign");
            if (id.equals(b)) {
                // loan B is tied to its payment account: the same amount on another account is not offered
                debit(api, second, emi.toPlainString(), due, "wrong account");
            }
            expected.put(id, in);
        }

        JsonNode all = api.getJson("/api/v1/loans/match-suggestions", 200);
        assertEquals(3, all.size());
        for (JsonNode row : all) {
            UUID id = UUID.fromString(row.get("loanId").asText());
            assertEquals(expected.get(id), candidateIds(row.get("suggestions")), row.get("loanName").asText());
            JsonNode single = api.getJson("/api/v1/loans/" + id + "/match-suggestions", 200);
            assertEquals(single.get("suggestions"), row.get("suggestions"), row.get("loanName").asText());
        }
    }

    @Test
    void loanQueryCountDoesNotGrowWithLoans() throws Exception {
        for (int i = 0; i < 3; i++) {
            loanWithMatch(i);
        }
        queriesFor("/api/v1/loans/match-suggestions"); // warm up
        long small = queriesFor("/api/v1/loans/match-suggestions");
        for (int i = 3; i < 13; i++) {
            loanWithMatch(i);
        }
        long large = queriesFor("/api/v1/loans/match-suggestions");
        assertEquals(small, large, "loan match-suggestions grew with the number of loans");
        assertTrue(large <= 9, "loan match-suggestions used " + large + " queries");
        assertEquals(13, api.getJson("/api/v1/loans/match-suggestions", 200).size());
    }

    private void loanWithMatch(int i) throws Exception {
        UUID id = loan("Loan " + i, String.valueOf(12000 + 5000 * i), i % 2 == 0 ? bank : null);
        JsonNode inst = firstInstallment(id);
        debit(api, bank, new BigDecimal(inst.get("emi").asText()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                LocalDate.parse(inst.get("dueDate").asText()), "emi " + i);
    }

    // --- exact windows (no bounding box) ----------------------------------------------------------

    private Set<String> candidatesAny(List<MatchWindow> windows) {
        return transactionTemplate.execute(status -> {
            em.unwrap(Session.class).enableFilter("userFilter").setParameter("userId", userId.toString());
            Set<String> ids = new TreeSet<>();
            transactionRepository.findMatchCandidatesAny(TransactionType.DEBIT, windows).forEach(t -> ids.add(t.getId().toString()));
            return ids;
        });
    }

    @Test
    void farApartWindowsNeverLoadTheTransactionsBetweenThem() throws Exception {
        LocalDate d1 = today.minusDays(1000);
        LocalDate d2 = today.minusDays(10);
        UUID second = account(api, "Second Bank", "4444");
        String inW1 = debit(api, bank, "500.00", d1, "in w1").toString();
        String inW2 = debit(api, bank, "500000.00", d2, "in w2").toString();
        // inside the min/max bounding box of both windows, inside neither window
        debit(api, bank, "250000.00", today.minusDays(500), "between both");
        debit(api, bank, "500.00", d2, "w1 amount at w2 date");
        debit(api, bank, "500000.00", d1, "w2 amount at w1 date");
        debit(other, otherBank, "500.00", d1, "foreign in w1");
        String otherAccount = debit(api, second, "500.00", d1, "other account in w1").toString();

        MatchWindow w1 = new MatchWindow(new BigDecimal("480"), new BigDecimal("520"), d1.minusDays(7), d1.plusDays(7), null);
        MatchWindow w2 = new MatchWindow(new BigDecimal("499980"), new BigDecimal("500020"), d2.minusDays(7), d2.plusDays(7), null);
        assertEquals(new TreeSet<>(List.of(inW1, inW2, otherAccount)), candidatesAny(List.of(w1, w2)));

        MatchWindow w1Bank = new MatchWindow(w1.minAmount(), w1.maxAmount(), w1.minDate(), w1.maxDate(), bank);
        assertEquals(new TreeSet<>(List.of(inW1, inW2)), candidatesAny(List.of(w1Bank, w2)));
    }

    @Test
    void identicalWindowsAreDedupedAndChunkedAtTwoHundred() throws Exception {
        String hit = debit(api, bank, "500.00", today.minusDays(5), "hit").toString();
        List<MatchWindow> windows = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            BigDecimal base = new BigDecimal(100000 + i * 100);
            windows.add(new MatchWindow(base, base.add(new BigDecimal("20")), today.minusDays(9), today, null));
        }
        windows.add(new MatchWindow(new BigDecimal("480"), new BigDecimal("520"), today.minusDays(9), today, null));
        windows.addAll(List.copyOf(windows)); // every window twice
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        // statistics are global: keep the job worker's poll out of the count
        jobWorker.pause();
        try {
            stats.clear();
            assertEquals(Set.of(hit), candidatesAny(windows));
            // 451 distinct windows -> 3 chunk queries (plus none for the duplicates)
            assertEquals(3, stats.getQueryExecutionCount());
        } finally {
            jobWorker.resume();
        }
    }
}
