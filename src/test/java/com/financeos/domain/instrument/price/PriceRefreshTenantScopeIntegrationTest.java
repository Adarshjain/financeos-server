package com.financeos.domain.instrument.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.core.security.UserFilterEnabler;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code refresh} runs with no transaction (the fetches must not hold a connection), so the tenant filter is
 * enabled in the short transaction that looks the targets up: a user's refresh sees only that user's holdings,
 * the scheduled job (no user in the context) sees everyone's. Real persistence and filter wiring (H2); the
 * provider is a stub that records what it was asked for and whether a transaction was open.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceRefreshTenantScopeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private InstrumentPriceRepository priceRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserFilterEnabler userFilterEnabler;
    @MockBean private PriceRefreshEventListener priceListener;

    private UUID aliceId;
    private UUID bobId;
    private UUID aliceInstrument;
    private UUID bobInstrument;
    private final List<UUID> instruments = new ArrayList<>();
    private final List<UUID> fetched = new CopyOnWriteArrayList<>();
    private final List<Boolean> fetchInTransaction = new CopyOnWriteArrayList<>();
    private PriceRefreshService service;

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String aliceEmail = "pr-scope-a-" + UUID.randomUUID() + "@example.test";
        String bobEmail = "pr-scope-b-" + UUID.randomUUID() + "@example.test";
        ApiTestClient alice = ApiTestClient.signUp(mockMvc, mapper, aliceEmail);
        ApiTestClient bob = ApiTestClient.signUp(mockMvc, mapper, bobEmail);
        aliceId = userRepository.findByEmail(aliceEmail).orElseThrow().getId();
        bobId = userRepository.findByEmail(bobEmail).orElseThrow().getId();
        aliceInstrument = holds(alice, "A", tag, 1);
        bobInstrument = holds(bob, "B", tag, 2);

        PriceProvider provider = new PriceProvider() {
            @Override public PriceSource source() { return PriceSource.YAHOO; }
            @Override public boolean supports(Instrument instrument) { return instruments.contains(instrument.getId()); }
            @Override public Map<UUID, PriceQuote> fetch(List<Instrument> toFetch) {
                fetchInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
                Map<UUID, PriceQuote> quotes = new java.util.HashMap<>();
                for (Instrument i : toFetch) {
                    fetched.add(i.getId());
                    quotes.put(i.getId(), new PriceQuote(new BigDecimal("100"), AppTime.today(), AppTime.nowInstant()));
                }
                return quotes;
            }
        };
        service = new PriceRefreshService(instrumentRepository, priceRepository, holdingRepository, List.of(provider),
                new PriceProperties(), mock(InstrumentClassificationService.class), transactionManager, userFilterEnabler);
    }

    private UUID holds(ApiTestClient user, String who, String tag, int n) throws Exception {
        UUID instrument = user.create("/api/v1/instruments", Map.of("type", "stock", "name", "Scope " + who + " " + tag,
                "symbol", "SC" + who + tag.substring(0, 4), "exchange", "NSE", "isin", "INE" + tag + "S" + n));
        instruments.add(instrument);
        UUID broker = user.create("/api/v1/accounts", Map.of("type", "broker", "name", "Scope " + who, "provider", "Zerodha",
                "clientId", "SC" + who + tag, "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        user.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", "buy", "quantity", "10", "price", "100",
                "tradeDate", AppTime.today().minusDays(30).toString()));
        return instrument;
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        for (UUID id : List.of(aliceId, bobId)) {
            jdbc.update("DELETE FROM investment_transactions WHERE holding_id IN (SELECT id FROM holdings WHERE user_id = ?)", id.toString());
            jdbc.update("DELETE FROM holdings WHERE user_id = ?", id.toString());
        }
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
        UserDataCleanup.deleteInstruments(jdbc, instruments);
    }

    @Test
    void aUsersRefreshFetchesAndWritesOnlyThatUsersInstruments() {
        UserContext.setCurrentUserId(aliceId);

        PriceRefreshResult result = service.refresh(Optional.empty());

        assertThat(fetched).containsExactly(aliceInstrument);
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(aliceInstrument, AppTime.today())).isPresent();
        assertThat(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(bobInstrument, AppTime.today())).isEmpty();
    }

    @Test
    void theOtherUserSeesOnlyTheirOwnToo() {
        UserContext.setCurrentUserId(bobId);

        service.refresh(Optional.empty());

        assertThat(fetched).containsExactly(bobInstrument);
    }

    @Test
    void theScheduledJobWithNoUserRefreshesEveryonesInstruments() {
        UserContext.clear();

        PriceRefreshResult result = service.refresh(Optional.empty());

        assertThat(Set.copyOf(fetched)).contains(aliceInstrument, bobInstrument);
        assertThat(result.refreshed()).isGreaterThanOrEqualTo(2);
        assertThat(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(aliceInstrument, AppTime.today())).isPresent();
        assertThat(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(bobInstrument, AppTime.today())).isPresent();
    }

    @Test
    void theNetworkFetchRunsOutsideAnyTransaction() {
        UserContext.setCurrentUserId(aliceId);

        service.refresh(Optional.empty());

        assertThat(fetchInTransaction).containsExactly(false);
    }
}
