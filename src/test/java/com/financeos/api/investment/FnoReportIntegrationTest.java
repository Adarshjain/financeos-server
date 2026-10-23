package com.financeos.api.investment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The F&amp;O list page, summary and ITR export, through the real endpoints. A trade's date is its
 * exit date, else its entry date; FY 2026 is 1 Apr 2026 to 31 Mar 2027.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FnoReportIntegrationTest {

    private static final String FNO = "/api/v1/investments/fno";
    private static final String PASSWORD = "fnoReportPass123!";
    private static final String WEIRD_SYMBOL = "NIFTY,\"WK\" 24000CE";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final List<String> emails = new ArrayList<>();
    private final Map<Cookie, List<UUID>> accountsBySession = new HashMap<>();
    private final Map<Cookie, List<UUID>> tradesBySession = new HashMap<>();
    private Cookie session;
    private UUID broker1;
    private UUID broker2;
    private final Map<String, UUID> tradeIds = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        session = authenticate("fno-report-" + UUID.randomUUID() + "@example.test");
        broker1 = broker(session, "Fno Broker One");
        broker2 = broker(session, "Fno Broker Two");

        // FY 2025: 31 Mar 2026 is its last day.
        trade("A", broker1, "NIFTY26MARFUT", "NIFTY", "future", null, null, "2026-03-31", "2026-03-31", 1000, 1100, 0);
        // FY 2026
        trade("B", broker1, "NIFTY26APR24000PE", "NIFTY", "option", "PE", 24000, "2026-03-20", "2026-04-01", 2000, 1500, 50);
        trade("C", broker1, "BANKNIFTY26APRFUT", "BANKNIFTY", "future", null, null, "2026-04-10", "2026-04-15", 5000, 5800, 20);
        trade("D", broker1, "NIFTY27MAR24000CE", "NIFTY", "option", "CE", 24000, "2027-03-01", "2027-03-31", 100, 400, 10);
        // no exit date: dated by its entry date
        trade("F", broker1, "RELIANCE26SEP1400PE", "RELIANCE", "option", "PE", 1400, "2026-09-10", null, 100, 1100, 0);
        trade("G", broker2, WEIRD_SYMBOL, "NIFTY", "option", "CE", 24000, "2026-05-01", "2026-05-10", 500, 300, 0);
        // FY 2027: 1 Apr 2027 is its first day.
        trade("E", broker1, "NIFTY27APR24000CE", "NIFTY", "option", "CE", 24000, "2027-04-01", "2027-04-01", 100, 150, 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Map.Entry<Cookie, List<UUID>> e : tradesBySession.entrySet()) {
            for (UUID id : e.getValue()) {
                mockMvc.perform(delete(FNO + "/" + id).cookie(e.getKey())).andExpect(status().isNoContent());
            }
        }
        for (Map.Entry<Cookie, List<UUID>> e : accountsBySession.entrySet()) {
            for (UUID id : e.getValue()) {
                mockMvc.perform(delete("/api/v1/accounts/" + id).cookie(e.getKey())).andExpect(status().isOk());
            }
        }
        for (String email : emails) {
            userRepository.findByEmail(email).map(User::getId).ifPresent(userRepository::deleteById);
        }
    }

    // --- legacy list ----------------------------------------------------------------------------------------

    @Test
    void legacyListKeepsItsShapeAndReturnsEverything() throws Exception {
        JsonNode body = getJson(FNO);
        assertTrue(body.has("trades"));
        assertTrue(body.has("totalRealizedPnl"));
        assertFalse(body.has("content"));
        assertEquals(7, body.get("trades").size());
        // 100 - 550 + 780 + 290 + 1000 - 200 + 50
        assertEquals(0, new java.math.BigDecimal("1470").compareTo(body.get("totalRealizedPnl").decimalValue()));
        assertTrue(body.get("trades").get(0).has("brokerAccountName"));
    }

    // --- paged list ------------------------------------------------------------------------------------------

    @Test
    void pageDefaultsToNewestFirstAndReportsSpringPageFields() throws Exception {
        JsonNode body = getJson(FNO + "/page");
        assertEquals(List.of("E", "D", "F", "G", "C", "B", "A"), names(body));
        assertEquals(7, body.get("totalElements").asInt());
        assertEquals(1, body.get("totalPages").asInt());
        assertEquals(0, body.get("number").asInt());
        assertEquals(25, body.get("size").asInt());
    }

    @Test
    void pageSplitsIntoPagesAndStaysInOrder() throws Exception {
        JsonNode first = getJson(FNO + "/page?fy=2026&size=2&page=0");
        JsonNode second = getJson(FNO + "/page?fy=2026&size=2&page=1");
        JsonNode third = getJson(FNO + "/page?fy=2026&size=2&page=2");
        assertEquals(List.of("D", "F"), names(first));
        assertEquals(List.of("G", "C"), names(second));
        assertEquals(List.of("B"), names(third));
        assertEquals(5, first.get("totalElements").asInt());
        assertEquals(3, first.get("totalPages").asInt());
        assertEquals(2, first.get("size").asInt());
        assertEquals(2, third.get("number").asInt());
    }

    @Test
    void sortsByDateAndPnl() throws Exception {
        assertEquals(List.of("B", "C", "G", "F", "D"), names(getJson(FNO + "/page?fy=2026&sort=oldest")));
        assertEquals(List.of("D", "F", "G", "C", "B"), names(getJson(FNO + "/page?fy=2026&sort=newest")));
        // F +1000, C +780, D +290, G -200, B -550
        assertEquals(List.of("F", "C", "D", "G", "B"), names(getJson(FNO + "/page?fy=2026&sort=pnl_desc")));
        assertEquals(List.of("B", "G", "D", "C", "F"), names(getJson(FNO + "/page?fy=2026&sort=pnl_asc")));
    }

    @Test
    void financialYearBoundariesAreInclusive() throws Exception {
        assertEquals(List.of("A"), names(getJson(FNO + "/page?fy=2025")));
        assertEquals(List.of("D", "F", "G", "C", "B"), names(getJson(FNO + "/page?fy=2026")));
        assertEquals(List.of("E"), names(getJson(FNO + "/page?fy=2027")));
    }

    @Test
    void filtersByContractTypeBrokerAndUnderlying() throws Exception {
        assertEquals(List.of("D", "G"), names(getJson(FNO + "/page?fy=2026&optionType=CE")));
        assertEquals(List.of("F", "B"), names(getJson(FNO + "/page?fy=2026&optionType=PE")));
        assertEquals(List.of("C"), names(getJson(FNO + "/page?fy=2026&optionType=FUT")));
        assertEquals(List.of("G"), names(getJson(FNO + "/page?brokerAccountId=" + broker2)));
        assertEquals(List.of("D", "G", "B"), names(getJson(FNO + "/page?fy=2026&underlying=nifty")));
        assertEquals(List.of("F"), names(getJson(FNO + "/page?underlying=RELIANCE&optionType=PE")));
        assertEquals(0, getJson(FNO + "/page?underlying=NOSUCH").get("totalElements").asInt());
    }

    @Test
    void invalidPagingSortAndOptionTypeAreRejected() throws Exception {
        mockMvc.perform(get(FNO + "/page?sort=bogus").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(FNO + "/page?size=0").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(FNO + "/page?size=1000").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(FNO + "/page?page=-1").cookie(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(FNO + "/page?optionType=XX").cookie(session)).andExpect(status().isBadRequest());
    }

    // --- summary ---------------------------------------------------------------------------------------------

    @Test
    void summaryTotalsMonthsAndUnderlyings() throws Exception {
        JsonNode s = getJson(FNO + "/summary?fy=2026");
        assertEquals(2026, s.get("fy").asInt());
        assertEquals("2026-04-01", s.get("fromDate").asText());
        assertEquals("2027-03-31", s.get("toDate").asText());
        assertEquals(1320, s.get("realisedPnl").asInt());
        assertEquals(5, s.get("tradeCount").asInt());
        assertEquals(60.0, s.get("winRate").asDouble(), 0.0001);
        assertEquals(80, s.get("totalCharges").asInt());

        JsonNode months = s.get("byMonth");
        assertEquals(12, months.size());
        assertEquals("2026-04", months.get(0).get("month").asText());
        assertEquals("2027-03", months.get(11).get("month").asText());
        Map<String, Integer> pnl = new HashMap<>();
        months.forEach(m -> pnl.put(m.get("month").asText(), m.get("pnl").asInt()));
        assertEquals(230, pnl.get("2026-04"));
        assertEquals(-200, pnl.get("2026-05"));
        assertEquals(0, pnl.get("2026-06"));
        assertEquals(1000, pnl.get("2026-09"));
        assertEquals(290, pnl.get("2027-03"));

        JsonNode by = s.get("byUnderlying");
        assertEquals(3, by.size());
        assertEquals("RELIANCE", by.get(0).get("underlying").asText());
        assertEquals(1000, by.get(0).get("pnl").asInt());
        assertEquals(1, by.get(0).get("trades").asInt());
        assertEquals("BANKNIFTY", by.get(1).get("underlying").asText());
        assertEquals("NIFTY", by.get(2).get("underlying").asText());
        assertEquals(-460, by.get(2).get("pnl").asInt());
        assertEquals(3, by.get(2).get("trades").asInt());
    }

    @Test
    void summaryFiltersByBrokerAccountAndRespectsYearEdges() throws Exception {
        JsonNode one = getJson(FNO + "/summary?fy=2026&brokerAccountId=" + broker1);
        assertEquals(4, one.get("tradeCount").asInt());
        assertEquals(1520, one.get("realisedPnl").asInt());
        assertEquals(75.0, one.get("winRate").asDouble(), 0.0001);

        JsonNode prev = getJson(FNO + "/summary?fy=2025");
        assertEquals(1, prev.get("tradeCount").asInt());
        assertEquals(100, prev.get("realisedPnl").asInt());
        JsonNode next = getJson(FNO + "/summary?fy=2027");
        assertEquals(1, next.get("tradeCount").asInt());
        assertEquals(50, next.get("realisedPnl").asInt());
    }

    @Test
    void summaryWithNoTradesIsZeroFilledWithZeroWinRate() throws Exception {
        JsonNode s = getJson(FNO + "/summary?fy=2030");
        assertEquals(0, s.get("tradeCount").asInt());
        assertEquals(0, s.get("winRate").asInt());
        assertEquals(0, s.get("realisedPnl").asInt());
        assertEquals(0, s.get("totalCharges").asInt());
        assertEquals(12, s.get("byMonth").size());
        s.get("byMonth").forEach(m -> assertEquals(0, m.get("pnl").asInt()));
        assertEquals(0, s.get("byUnderlying").size());
    }

    @Test
    void summaryDefaultsToTheCurrentFinancialYear() throws Exception {
        JsonNode s = getJson(FNO + "/summary");
        assertEquals(com.financeos.domain.investment.FinancialYear.current(), s.get("fy").asInt());
        assertEquals(12, s.get("byMonth").size());
    }

    @Test
    void otherUsersTradesAreNeverCounted() throws Exception {
        Cookie other = authenticate("fno-report-other-" + UUID.randomUUID() + "@example.test");
        UUID otherBroker = broker(other, "Other Broker");
        trade(other, "X", otherBroker, "NIFTY26JUN24000CE", "NIFTY", "option", "CE", 24000, "2026-06-01", "2026-06-02", 100, 10100, 0);

        assertEquals(5, getJson(FNO + "/summary?fy=2026").get("tradeCount").asInt());
        assertEquals(7, getJson(FNO + "/page").get("totalElements").asInt());
        assertEquals(7, getJson(FNO).get("trades").size());
        JsonNode theirs = getJson(other, FNO + "/summary?fy=2026");
        assertEquals(1, theirs.get("tradeCount").asInt());
        assertEquals(10000, theirs.get("realisedPnl").asInt());
    }

    // --- export ----------------------------------------------------------------------------------------------

    @Test
    void exportListsTheYearsTradesOldestFirstWithATotalsRow() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get(FNO + "/export.csv?fy=2026").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(res.getContentType().startsWith("text/csv"));
        assertEquals("attachment; filename=\"fno-2026-27.csv\"", res.getHeader("Content-Disposition"));

        String csv = res.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿"));
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals("Trade date,Underlying,Contract,Strike,Type,Quantity,Buy value,Sell value,Charges,Realised P&L,Turnover (absolute P&L)", lines[0]);
        assertEquals(7, lines.length);
        assertEquals("2026-04-01,NIFTY,NIFTY26APR24000PE,24000,PE,1,2000,1500,50,-550,550", lines[1]);
        assertEquals("2026-04-15,BANKNIFTY,BANKNIFTY26APRFUT,,FUT,1,5000,5800,20,780,780", lines[2]);
        // comma and quotes in the contract are quoted and doubled
        assertEquals("2026-05-10,NIFTY,\"NIFTY,\"\"WK\"\" 24000CE\",24000,CE,1,500,300,0,-200,200", lines[3]);
        assertEquals("2026-09-10,RELIANCE,RELIANCE26SEP1400PE,1400,PE,1,100,1100,0,1000,1000", lines[4]);
        assertEquals("2027-03-31,NIFTY,NIFTY27MAR24000CE,24000,CE,1,100,400,10,290,290", lines[5]);
        // turnover is the sum of |P&L| per trade: 550+780+200+1000+290
        assertEquals("Total,,,,,,7700,9100,80,1320,2820", lines[6]);
    }

    @Test
    void tradeWithoutUnderlyingIsFoundByItsTradingSymbol() throws Exception {
        trade("N", broker1, "ZZTEST26APRFUT", "ZZTEST", "future", null, null, "2026-06-01", "2026-06-02", 100, 150, 0);
        trade("M", broker1, "YYTEST26APRFUT", "YYTEST", "future", null, null, "2026-06-01", "2026-06-02", 100, 150, 0);
        // the API derives a missing underlying, so blank the stored one directly (as an import can leave it)
        jdbc.update("UPDATE fno_trades SET underlying_symbol = NULL WHERE id = ?", tradeIds.get("N").toString());
        jdbc.update("UPDATE fno_trades SET underlying_symbol = '  ' WHERE id = ?", tradeIds.get("M").toString());
        assertEquals(List.of("N"), names(getJson(FNO + "/page?underlying=zztest26aprfut")));
        assertEquals(List.of("M"), names(getJson(FNO + "/page?underlying=YYTEST26APRFUT")));
        // a trade with an underlying is still matched by it, not by its contract
        assertEquals(0, getJson(FNO + "/page?underlying=NIFTY26APR24000PE").get("totalElements").asInt());
    }

    @Test
    void exportDefusesLeadingMinusInTextCellsButNotNumbers() throws Exception {
        trade("X", broker1, "-CMD26APRFUT", "-NEG", "future", null, null, "2026-06-01", "2026-06-02", 200, 100, 0);
        String csv = mockMvc.perform(get(FNO + "/export.csv?fy=2026").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // the loss (-100) is a number and stays bare; the text cells are prefixed
        assertTrue(csv.contains("2026-06-02,'-NEG,'-CMD26APRFUT,,FUT,1,200,100,0,-100,100\r\n"));
    }

    @Test
    void exportOfAnEmptyYearHasOnlyHeaderAndZeroTotals() throws Exception {
        String csv = mockMvc.perform(get(FNO + "/export.csv?fy=2030").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals(2, lines.length);
        assertEquals("Total,,,,,,0,0,0,0,0", lines[1]);
    }

    @Test
    void exportFinancialYearEdgesAreInclusive() throws Exception {
        String prev = mockMvc.perform(get(FNO + "/export.csv?fy=2025").cookie(session))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(prev.contains("2026-03-31,NIFTY,NIFTY26MARFUT"));
        assertFalse(prev.contains("26APR"));
        String next = mockMvc.perform(get(FNO + "/export.csv?fy=2027").cookie(session))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(next.contains("2027-04-01,NIFTY,NIFTY27APR24000CE"));
        assertFalse(next.contains("27MAR"));
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private List<String> names(JsonNode page) {
        Map<UUID, String> byId = tradeIds.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(n -> byId.get(UUID.fromString(n.get("id").asText()))).toList();
    }

    private void trade(String name, UUID brokerId, String symbol, String underlying, String contractType, String optionType,
                       Integer strike, String entry, String exit, int buy, int sell, int charges) throws Exception {
        trade(session, name, brokerId, symbol, underlying, contractType, optionType, strike, entry, exit, buy, sell, charges);
    }

    private void trade(Cookie cookie, String name, UUID brokerId, String symbol, String underlying, String contractType,
                       String optionType, Integer strike, String entry, String exit, int buy, int sell, int charges) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("brokerAccountId", brokerId.toString());
        body.put("tradingSymbol", symbol);
        body.put("underlyingSymbol", underlying);
        body.put("contractType", contractType);
        body.put("optionType", optionType);
        body.put("strikePrice", strike);
        body.put("quantity", 1);
        body.put("buyValue", buy);
        body.put("sellValue", sell);
        body.put("totalCharges", charges);
        body.put("entryDate", entry);
        body.put("exitDate", exit);
        JsonNode created = postJson(cookie, FNO, body);
        UUID id = UUID.fromString(created.get("id").asText());
        if (cookie == session) {
            tradeIds.put(name, id);
        }
        tradesBySession.computeIfAbsent(cookie, k -> new ArrayList<>()).add(id);
    }

    private UUID broker(Cookie cookie, String name) throws Exception {
        UUID id = UUID.fromString(postJson(cookie, "/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", "Zerodha",
                "clientId", "CL" + UUID.randomUUID().toString().substring(0, 6), "cashBalance", 0, "financialPosition", "asset",
                "excludeFromNetAsset", false)).get("id").asText());
        accountsBySession.computeIfAbsent(cookie, k -> new ArrayList<>()).add(id);
        return id;
    }

    private JsonNode getJson(String path) throws Exception {
        return getJson(session, path);
    }

    private JsonNode getJson(Cookie cookie, String path) throws Exception {
        String body = mockMvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private JsonNode postJson(Cookie cookie, String path, Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)).cookie(cookie))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private Cookie authenticate(String email) throws Exception {
        emails.add(email);
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD, "inviteCode", "test-invite-code"))));
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("FINANCEOS_SESSION");
    }
}
