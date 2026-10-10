package com.financeos.domain.instrument.corporateaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.investment.InvestmentService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner-scoped reads against real persistence (H2): a null owner matches NOTHING — not the owner-less
 * rows a derived query's {@code IS NULL} would return — so an owner-less holding gets no corporate
 * action, while each owner still reads exactly their own rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OwnerlessReadsMatchNothingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CorporateActionRepository corporateActionRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private InvestmentService investmentService;
    @Autowired private TransactionTemplate tx;

    private UUID aliceId;
    private UUID instrument;
    private UUID child;
    private UUID orphanHolding;
    private UUID ownerlessSplit;
    private UUID ownerlessDemerger;
    private UUID alicesSplit;
    private final List<UUID> instruments = new ArrayList<>();
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String email = "ownerless-" + UUID.randomUUID() + "@example.test";
        ApiTestClient alice = ApiTestClient.signUp(mockMvc, mapper, email);
        aliceId = userRepository.findByEmail(email).orElseThrow().getId();
        instrument = alice.create("/api/v1/instruments", Map.of("type", "stock", "name", "Ownerless " + tag,
                "symbol", "OWN" + tag.substring(0, 4), "exchange", "NSE", "isin", "INE" + tag + "O1"));
        child = alice.create("/api/v1/instruments", Map.of("type", "stock", "name", "Ownerless Child " + tag,
                "symbol", "OWC" + tag.substring(0, 4), "exchange", "NSE", "isin", "INE" + tag + "O2"));
        instruments.addAll(List.of(instrument, child));
        UUID broker = alice.create("/api/v1/accounts", Map.of("type", "broker", "name", "Own", "provider", "Zerodha",
                "clientId", "OWN1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        alice.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", "buy", "quantity", "10", "price", "100",
                "tradeDate", today.minusDays(300).toString()));
        orphanHolding = UUID.fromString(jdbc.queryForObject("SELECT id FROM holdings WHERE user_id = ? AND instrument_id = ?",
                String.class, aliceId.toString(), instrument.toString()));
        // The holding loses its owner (as rows from before owners existed); its trade stays.
        jdbc.update("UPDATE holdings SET user_id = NULL WHERE id = ?", orphanHolding.toString());

        ownerlessSplit = action(null, instrument, "split", null, 100);
        ownerlessDemerger = action(null, instrument, "demerger", child, 80);
        alicesSplit = action(aliceId, instrument, "split", null, 50);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        jdbc.update("DELETE FROM corporate_actions WHERE id IN (?, ?)", ownerlessSplit.toString(), ownerlessDemerger.toString());
        jdbc.update("DELETE FROM investment_transactions WHERE holding_id = ?", orphanHolding.toString());
        jdbc.update("DELETE FROM holdings WHERE id = ?", orphanHolding.toString());
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId));
        UserDataCleanup.deleteInstruments(jdbc, instruments);
    }

    private UUID action(UUID owner, UUID on, String type, UUID target, int daysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corporate_actions (id, user_id, instrument_id, type, ratio_from, ratio_to, ex_date, "
                        + "target_instrument_id, cost_allocation_pct, created_at) VALUES (?, ?, ?, ?, 1, 2, ?, ?, ?, CURRENT_TIMESTAMP)",
                id.toString(), owner == null ? null : owner.toString(), on.toString(), type,
                Date.valueOf(today.minusDays(daysAgo)), target == null ? null : target.toString(),
                target == null ? null : new BigDecimal("10"));
        return id;
    }

    private static List<UUID> ids(List<CorporateAction> actions) {
        return actions.stream().map(CorporateAction::getId).toList();
    }

    @Test
    void aNullOwnerMatchesNoOwnerlessCorporateAction() {
        assertTrue(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(null, instrument).isEmpty());
        assertTrue(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(null, child).isEmpty());
        assertTrue(corporateActionRepository.findByIdAndUser_Id(ownerlessSplit, null).isEmpty());
        assertTrue(corporateActionRepository.findOwnedByInstrumentIds(null, List.of(instrument)).isEmpty());
        assertTrue(corporateActionRepository.findOwnedByTargetInstrumentIds(null, List.of(child)).isEmpty());
        assertTrue(corporateActionRepository.findOwnedInvolving(null, List.of(instrument, child)).isEmpty());
        assertTrue(corporateActionRepository.findAllOwnedWithInstruments(null).isEmpty());
    }

    @Test
    void anOwnerStillReadsExactlyTheirOwn() {
        assertEquals(List.of(alicesSplit), ids(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(aliceId, instrument)));
        assertTrue(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(aliceId, child).isEmpty());
        assertTrue(corporateActionRepository.findByIdAndUser_Id(alicesSplit, aliceId).isPresent());
        assertTrue(corporateActionRepository.findByIdAndUser_Id(ownerlessSplit, aliceId).isEmpty());
    }

    @Test
    void aNullOwnerMatchesNoOwnerlessHolding() {
        assertTrue(holdingRepository.findByUser_IdAndInstrument_Id(null, instrument).isEmpty());
        assertTrue(holdingRepository.findAllWithDetailsOfUser(null).isEmpty());
        assertTrue(holdingRepository.findByUser_IdAndInstrument_Id(aliceId, instrument).isEmpty(), "the holding is no longer hers");
        assertTrue(investmentService.figuresOf(aliceId).isEmpty());
    }

    @Test
    void anOwnerlessHoldingGetsNoCorporateAction() {
        BigDecimal qty = tx.execute(status -> {
            Holding orphan = holdingRepository.findById(orphanHolding).orElseThrow();
            return investmentService.calculateHoldingPosition(orphan).openQty();
        });
        // Neither the owner-less split nor Alice's own split applies: 10 bought, 10 open.
        assertEquals(0, BigDecimal.TEN.compareTo(qty), String.valueOf(qty));
    }
}
