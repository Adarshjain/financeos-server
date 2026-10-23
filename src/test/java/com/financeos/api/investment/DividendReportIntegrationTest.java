package com.financeos.api.investment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.investment.FinancialYear;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.dividend.DividendRepository;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dividend per-instrument totals, the FY filter and the CSV export, through the real endpoints.
 * Dates are relative to the current financial year so the "last N years" window is stable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DividendReportIntegrationTest {

    private static final String DIV = "/api/v1/investments/dividends";
    private static final String PASSWORD = "dividendReportPass123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private DividendRepository dividendRepository;
    @Autowired private InvestmentTransactionRepository investmentTransactionRepository;

    private String email;
    private Cookie session;
    private int cur;
    private UUID brokerId;
    private UUID instrumentX;
    private UUID instrumentY;
    private final String nameX = "Alpha, \"Quoted\" Ltd " + UUID.randomUUID().toString().substring(0, 6);
    private final String nameY = "Beta Ltd " + UUID.randomUUID().toString().substring(0, 6);
    private final List<UUID> dividendIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cur = FinancialYear.current();
        email = "div-report-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email);
        brokerId = id(postJson("/api/v1/accounts", Map.of("type", "broker", "name", "Report Broker", "provider", "Zerodha",
                "clientId", "CL1", "cashBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", false)));
        instrumentX = instrument(nameX);
        instrumentY = instrument(nameY);

        // X: last day of the previous FY, and first day of the current FY
        dividend(instrumentX, FinancialYear.end(cur - 1), 100, 10, null);
        dividend(instrumentX, FinancialYear.start(cur), 200, 20, 10);
        // Y: current FY, first day of the oldest of three FYs, and the day before it
        dividend(instrumentY, FinancialYear.start(cur).plusDays(60), 500, null, null);
        dividend(instrumentY, FinancialYear.start(cur - 2), 50, null, null);
        dividend(instrumentY, FinancialYear.start(cur - 2).minusDays(1), 9999, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        dividendRepository.deleteAllById(dividendIds);
        for (UUID instrumentId : List.of(instrumentX, instrumentY)) {
            holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerId, instrumentId).ifPresent(h -> {
                investmentTransactionRepository.deleteAll(investmentTransactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId()));
                holdingRepository.deleteById(h.getId());
            });
        }
        mockMvc.perform(delete("/api/v1/accounts/" + brokerId).cookie(session)).andExpect(status().isOk());
        userRepository.findByEmail(email).map(User::getId).ifPresent(userRepository::deleteById);
    }

    // --- by instrument --------------------------------------------------------------------------------------

    @Test
    void byInstrumentTotalsTheLastThreeFinancialYearsBiggestNetFirst() throws Exception {
        JsonNode page = getJson(DIV + "/by-instrument?years=3");
        assertEquals(2, page.get("totalElements").asInt());
        JsonNode content = page.get("content");
        assertEquals(instrumentY.toString(), content.get(0).get("instrumentId").asText());
        assertEquals(nameY, content.get(0).get("instrumentName").asText());
        assertEquals(550, content.get(0).get("gross").asInt());
        assertEquals(0, content.get(0).get("tds").asInt());
        assertEquals(550, content.get(0).get("net").asInt());
        assertEquals(2, content.get(0).get("payouts").asInt());

        assertEquals(instrumentX.toString(), content.get(1).get("instrumentId").asText());
        assertEquals(nameX, content.get(1).get("instrumentName").asText());
        assertEquals(300, content.get(1).get("gross").asInt());
        assertEquals(30, content.get(1).get("tds").asInt());
        assertEquals(270, content.get(1).get("net").asInt());
        assertEquals(2, content.get(1).get("payouts").asInt());
    }

    @Test
    void byInstrumentDefaultsToThreeYears() throws Exception {
        assertEquals(550, getJson(DIV + "/by-instrument").get("content").get(0).get("gross").asInt());
    }

    @Test
    void byInstrumentWindowGrowsAndShrinksWithYears() throws Exception {
        // four years reaches the 9,999 payout the day before the third year starts
        assertEquals(10549, getJson(DIV + "/by-instrument?years=4").get("content").get(0).get("gross").asInt());
        // one year is the current financial year only
        JsonNode one = getJson(DIV + "/by-instrument?years=1").get("content");
        assertEquals(instrumentY.toString(), one.get(0).get("instrumentId").asText());
        assertEquals(500, one.get(0).get("gross").asInt());
        assertEquals(1, one.get(0).get("payouts").asInt());
        assertEquals(200, one.get(1).get("gross").asInt());
        assertEquals(180, one.get(1).get("net").asInt());
    }

    @Test
    void byInstrumentIsPaginated() throws Exception {
        JsonNode first = getJson(DIV + "/by-instrument?years=3&size=1&page=0");
        JsonNode second = getJson(DIV + "/by-instrument?years=3&size=1&page=1");
        assertEquals(2, first.get("totalElements").asInt());
        assertEquals(2, first.get("totalPages").asInt());
        assertEquals(instrumentY.toString(), first.get("content").get(0).get("instrumentId").asText());
        assertEquals(instrumentX.toString(), second.get("content").get(0).get("instrumentId").asText());
        assertEquals(0, getJson(DIV + "/by-instrument?years=3&size=1&page=2").get("content").size());
    }

    @Test
    void byInstrumentRejectsOutOfRangeYears() throws Exception {
        mockMvc.perform(get(DIV + "/by-instrument?years=0").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(DIV + "/by-instrument?years=21").cookie(session)).andExpect(status().isBadRequest());
    }

    // --- fy filter ------------------------------------------------------------------------------------------

    @Test
    void fyFiltersTheListWithInclusiveBoundaries() throws Exception {
        // payouts on 31 Mar belong to the FY that ends, on 1 Apr to the one that starts
        assertEquals(List.of(100), amounts(getJson(DIV + "?fy=" + (cur - 1))));
        assertEquals(List.of(500, 200), amounts(getJson(DIV + "?fy=" + cur)));
        assertEquals(List.of(), amounts(getJson(DIV + "?fy=" + (cur + 1))));
    }

    @Test
    void fyIsRejectedTogetherWithFromOrTo() throws Exception {
        mockMvc.perform(get(DIV + "?fy=" + cur + "&from=2026-04-01").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(DIV + "/summary?fy=" + cur + "&to=2027-03-31").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(DIV + "/receipts/summary?fy=" + cur + "&from=2026-04-01").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(DIV + "/export.csv?fy=" + cur + "&from=2026-04-01").cookie(session)).andExpect(status().isBadRequest());
    }

    @Test
    void fyFiltersTheSummaryAndTheReceiptSummary() throws Exception {
        JsonNode summary = getJson(DIV + "/summary?fy=" + cur);
        assertEquals(700, summary.get("totalAmount").asInt());
        assertEquals(20, summary.get("totalTds").asInt());
        assertEquals(680, summary.get("totalNet").asInt());
        assertEquals(2, summary.get("totalCount").asInt());
        assertEquals(1, summary.get("buckets").size());

        assertEquals(5, getJson(DIV + "/summary").get("totalCount").asInt());
        assertEquals(2, getJson(DIV + "/receipts/summary?fy=" + cur).get("totalCount").asInt());
        assertEquals(1, getJson(DIV + "/receipts/summary?fy=" + (cur - 1)).get("totalCount").asInt());
        assertEquals(5, getJson(DIV + "/receipts/summary").get("totalCount").asInt());
    }

    @Test
    void fromToStillWorkOnSummaries() throws Exception {
        String from = FinancialYear.start(cur).toString();
        assertEquals(2, getJson(DIV + "/summary?from=" + from).get("totalCount").asInt());
        assertEquals(3, getJson(DIV + "/summary?to=" + FinancialYear.end(cur - 1)).get("totalCount").asInt());
    }

    // --- export ---------------------------------------------------------------------------------------------

    @Test
    void exportWritesHeaderAndOneRowPerMatchingDividend() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get(DIV + "/export.csv?fy=" + cur).cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(res.getContentType().startsWith("text/csv"));
        assertEquals("attachment; filename=\"dividends-" + FinancialYear.label(cur) + ".csv\"", res.getHeader("Content-Disposition"));

        String csv = res.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿"));
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals("Instrument,Broker account,Type,Ex-date,Pay date,Per unit,Quantity,Gross,TDS,Net,Receipt status,Bank transaction,Bank transaction date",
                lines[0]);
        assertEquals(3, lines.length);
        // newest first: Y (60 days in), then X on the first day
        assertTrue(lines[1].startsWith(nameY + ",Report Broker,dividend,," + FinancialYear.start(cur).plusDays(60) + ",,,500,0,500,"), lines[1]);
        // per unit 10 on 200 gross is 20 units; the name has a comma and quotes, so it is quoted and doubled
        String quotedX = "\"" + nameX.replace("\"", "\"\"") + "\"";
        assertTrue(lines[2].startsWith(quotedX + ",Report Broker,dividend,," + FinancialYear.start(cur) + ",10,20,200,20,180,"), lines[2]);
        // no linked bank credit: the last two columns are empty
        assertTrue(lines[2].endsWith(",,"), lines[2]);
    }

    @Test
    void exportWithoutFyIsNamedAllAndCoversEveryRow() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get(DIV + "/export.csv").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("attachment; filename=\"dividends-all.csv\"", res.getHeader("Content-Disposition"));
        assertEquals(6, res.getContentAsString(StandardCharsets.UTF_8).substring(1).split("\r\n").length);
    }

    @Test
    void exportAppliesTheListFilters() throws Exception {
        String csv = mockMvc.perform(get(DIV + "/export.csv?instrumentId=" + instrumentY + "&from=" + FinancialYear.start(cur - 2)).cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals(3, lines.length);
        assertFalse(csv.contains("Alpha"));
        assertFalse(csv.contains("9999"));
        // an unmatched filter leaves just the header
        String none = mockMvc.perform(get(DIV + "/export.csv?fy=" + (cur + 5)).cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(1, none.substring(1).split("\r\n").length);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private List<Integer> amounts(JsonNode page) {
        List<Integer> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("amount").asInt()));
        return out;
    }

    private UUID instrument(String name) throws Exception {
        String symbol = "DR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID instrumentId = id(postJson("/api/v1/instruments/resolve", Map.of("type", "stock", "name", name,
                "isin", "INE" + String.format("%08d", (int) (Math.random() * 1e8)) + "1", "symbol", symbol, "exchange", "NSE",
                "yahooSymbol", symbol + ".NS")));
        postJson("/api/v1/investments/transactions", Map.of("brokerAccountId", brokerId.toString(), "instrumentId", instrumentId.toString(),
                "type", "buy", "quantity", 10, "price", 100, "tradeDate", "2024-01-05"));
        return instrumentId;
    }

    private void dividend(UUID instrumentId, LocalDate payDate, int amount, Integer tds, Integer perUnit) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("brokerAccountId", brokerId.toString());
        body.put("instrumentId", instrumentId.toString());
        body.put("type", "dividend");
        body.put("amount", amount);
        body.put("tds", tds);
        body.put("perUnit", perUnit);
        body.put("payDate", payDate.toString());
        dividendIds.add(id(postJson(DIV, body)));
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asText());
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mockMvc.perform(get(path).cookie(session)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private JsonNode postJson(String path, Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)).cookie(session))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private Cookie authenticate(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD, "inviteCode", "test-invite-code"))));
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("FINANCEOS_SESSION");
    }
}
