package com.financeos.api.investment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendReceiptStatus;
import com.financeos.domain.investment.dividend.DividendReceiptWindows;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dividend.DividendType;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JPQL receipt filter ({@code DividendRepository.RECEIPT_PREDICATE}) must select exactly the rows
 * whose Java derivation ({@code DividendReceiptWindows.derive}) yields that status. The unit test proves
 * the threshold algebra against a Java transcription of the predicate; this test runs the real query,
 * through the real endpoint, for every source (with ex-date ≠ pay date on a suggested row so a wrong
 * base date would show), every coverage outcome, a linked row and both manual notes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DividendReceiptFilterIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private DividendRepository dividendRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InvestmentTransactionRepository investmentTransactionRepository;

    private final String password = "receiptFilterPass123!";
    private String email;
    private Cookie session;
    private User user;
    private LocalDate today;
    private LocalDate coverageEnd;
    private final Map<UUID, DividendReceiptStatus> expectedByRow = new HashMap<>();
    private UUID bankId;
    private UUID brokerId;
    private UUID creditId;
    private UUID holdingId;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        coverageEnd = today.minusDays(20);
        email = "receipt-filter-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, password);
        user = userRepository.findByEmail(email).orElseThrow();

        bankId = id(postJson("/api/v1/accounts", Map.of("type", "bank_account", "name", "Filter Bank", "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false)));
        brokerId = id(postJson("/api/v1/accounts", Map.of("type", "broker", "name", "Filter Broker", "provider", "Zerodha",
                "clientId", "CL1", "cashBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", false)));
        String symbol = "RF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID instrumentId = id(postJson("/api/v1/instruments/resolve", Map.of("type", "stock", "name", "Receipt Filter Ltd " + symbol,
                "isin", "INE" + String.format("%08d", (int) (Math.random() * 1e8)) + "1", "symbol", symbol, "exchange", "NSE",
                "yahooSymbol", symbol + ".NS")));
        postJson("/api/v1/investments/transactions", Map.of("brokerAccountId", brokerId.toString(), "instrumentId", instrumentId.toString(),
                "type", "buy", "quantity", 10, "price", 100, "tradeDate", "2026-01-05"));
        // The only bank credit fixes coverage at today − 20.
        creditId = id(postJson("/api/v1/transactions", Map.of("accountId", bankId.toString(), "amount", 900,
                "date", coverageEnd.toString(), "description", "ACH C- RECEIPT FILTER LTD DIVIDEND")));

        Holding holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerId, instrumentId).orElseThrow();
        holdingId = holding.getId();
        Transaction credit = transactionRepository.findById(creditId).orElseThrow();

        // manual: pay ±10
        seed("manual", null, today, null, null);                       // ends today+10 → awaiting
        seed("manual", null, today.minusDays(15), null, null);         // ends today−5 > coverage → unverifiable
        seed("manual", null, today.minusDays(35), null, null);         // ends today−25 ≤ coverage → overdue
        // import: pay ±5
        seed("import", null, today.minusDays(3), null, null);          // awaiting
        seed("import", null, today.minusDays(10), null, null);         // unverifiable
        seed("import", null, today.minusDays(30), null, null);         // overdue
        // suggested: ex-date −3/+60, pay date is a placeholder
        seed("suggested", today.minusDays(50), today.minusDays(50), null, null);  // awaiting
        seed("suggested", today.minusDays(70), today.minusDays(70), null, null);  // unverifiable
        seed("suggested", today.minusDays(90), today.minusDays(90), null, null);  // overdue
        seed("suggested", today.minusDays(90), today, null, null);                // ex-date rules: overdue although pay date is today
        seed("suggested", null, today.minusDays(90), null, null);                 // no ex-date → pay date → overdue
        // resolved rows
        seed("manual", null, today.minusDays(40), credit, null);                                   // received
        seed("manual", null, today.minusDays(40), null, DividendReceiptStatus.received_untracked);  // manual note
        seed("manual", null, today.minusDays(1), null, DividendReceiptStatus.not_received);         // note beats awaiting

        Set<DividendReceiptStatus> seen = new HashSet<>(expectedByRow.values());
        assertEquals(Set.of(DividendReceiptStatus.values()), seen, "fixture must cover every status");
    }

    /**
     * Shared H2 across @SpringBootTest classes — remove every row this test created. Whole-account
     * deletion ({@code /auth/me/delete}) enumerates Oracle's data dictionary and cannot run on H2, so
     * the dividends go through the repository and each account through its own delete endpoint,
     * whose service cascades holdings, trades and transactions; the user row goes last.
     */
    @AfterEach
    void tearDown() throws Exception {
        dividendRepository.deleteAllById(expectedByRow.keySet());
        investmentTransactionRepository.deleteAll(investmentTransactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holdingId));
        holdingRepository.deleteById(holdingId);
        transactionRepository.deleteById(creditId);
        for (UUID accountId : List.of(brokerId, bankId)) {
            mockMvc.perform(delete("/api/v1/accounts/" + accountId).cookie(session)).andExpect(status().isOk());
        }
        userRepository.deleteById(user.getId());
    }

    @Test
    void receiptFilterSelectsExactlyTheRowsTheJavaDerivationAssignsToEachStatus() throws Exception {
        for (DividendReceiptStatus status : DividendReceiptStatus.values()) {
            Set<UUID> expected = expectedByRow.entrySet().stream()
                    .filter(e -> e.getValue() == status).map(Map.Entry::getKey).collect(Collectors.toSet());
            assertFalse(expected.isEmpty(), status.name());
            assertEquals(expected, idsOf(list("?receipt=" + status.name() + "&size=100")), "receipt=" + status);
        }
        assertEquals(expectedByRow.keySet(), idsOf(list("?size=100")), "unfiltered list");
    }

    @Test
    void listRowsCarryTheSameDerivedStatusAsTheFilter() throws Exception {
        JsonNode content = list("?size=100");
        for (JsonNode row : content) {
            UUID id = UUID.fromString(row.get("id").asText());
            assertEquals(expectedByRow.get(id).name(), row.get("receiptStatus").asText(), "row " + id);
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private void seed(String source, LocalDate exDate, LocalDate payDate, Transaction linked, DividendReceiptStatus note) {
        Holding holding = holdingRepository.findById(holdingId).orElseThrow();
        Dividend d = new Dividend();
        d.setUser(user);
        d.setHolding(holding);
        d.setType(DividendType.dividend);
        d.setAmount(new BigDecimal("1000"));
        d.setSource(source);
        d.setExDate(exDate);
        d.setPayDate(payDate);
        d.setTransaction(linked);
        d.setReceiptStatus(note);
        Dividend saved = dividendRepository.save(d);
        expectedByRow.put(saved.getId(), DividendReceiptWindows.derive(source, exDate, payDate, linked != null, note, today, coverageEnd));
    }

    private JsonNode list(String query) throws Exception {
        String body = mockMvc.perform(get("/api/v1/investments/dividends" + query).cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("content");
    }

    private static Set<UUID> idsOf(JsonNode content) {
        Set<UUID> ids = new HashSet<>();
        for (JsonNode row : content) {
            ids.add(UUID.fromString(row.get("id").asText()));
        }
        return ids;
    }

    private JsonNode postJson(String path, Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)).cookie(session))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asText());
    }

    private Cookie authenticate(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password, "inviteCode", "test-invite-code"))));
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("FINANCEOS_SESSION");
    }
}
