package com.financeos.domain.instrument.price;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One instrument whose price write fails (here: at commit) must not fail the refresh: it is in the failed list
 * with the root cause, logged as ingest.item_failed, and the other instruments' prices are persisted.
 * The "database" keeps committed rows per transaction; a rolled back transaction's saves are discarded.
 */
class PriceRefreshItemFailureTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 9);
    private static final String ORA = "ORA-02290: check constraint (FOS.CHK_PRICE_CLOSE) violated";

    private final List<InstrumentPrice> committed = new ArrayList<>();
    private final List<InstrumentPrice> pending = new ArrayList<>();
    private UUID doomedId;
    private Instrument first;
    private Instrument second;
    private Instrument third;
    private PriceRefreshService service;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    private Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName(name);
        i.setSymbol(name.toUpperCase());
        i.setYahooSymbol(name.toUpperCase() + ".NS");
        return i;
    }

    @BeforeEach
    void setUp() {
        first = instrument("Infy");
        second = instrument("Tcs");
        third = instrument("Wipro");
        doomedId = second.getId();

        InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
        HoldingRepository holdings = mock(HoldingRepository.class);
        when(holdings.findDistinctActiveInstrumentIdsHeld()).thenReturn(List.of(first.getId(), second.getId(), third.getId()));
        when(instrumentRepository.findAllById(any())).thenReturn(List.of(first, second, third));

        InstrumentPriceRepository priceRepository = mock(InstrumentPriceRepository.class);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(any(), any())).thenReturn(Optional.empty());
        when(priceRepository.save(any(InstrumentPrice.class))).thenAnswer(inv -> {
            pending.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        PriceProvider provider = mock(PriceProvider.class);
        when(provider.source()).thenReturn(PriceSource.YAHOO);
        when(provider.supports(any())).thenReturn(true);
        Map<UUID, PriceQuote> quotes = new HashMap<>();
        for (Instrument i : List.of(first, second, third)) {
            quotes.put(i.getId(), new PriceQuote(new BigDecimal("100"), D));
        }
        when(provider.fetch(any())).thenReturn(quotes);

        // Commit throws for the doomed instrument's transaction, like a deferred constraint violation.
        PlatformTransactionManager tm = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition d) {
                pending.clear();
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                boolean doomed = pending.stream().anyMatch(p -> p.getInstrument().getId().equals(doomedId));
                if (doomed) {
                    pending.clear();
                    throw new DataIntegrityViolationException("could not execute statement",
                            new SQLException(ORA));
                }
                committed.addAll(pending);
                pending.clear();
            }

            @Override
            public void rollback(TransactionStatus status) {
                pending.clear();
            }
        };

        service = new PriceRefreshService(instrumentRepository, priceRepository, holdings, List.of(provider),
                new PriceProperties(), mock(InstrumentClassificationService.class), tm);

        logger = (Logger) LoggerFactory.getLogger(PriceRefreshService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void aCommitFailureOfTheSecondInstrumentLeavesTheFirstAndThirdPersisted() {
        PriceRefreshResult result = service.refresh(Optional.empty());

        Set<UUID> persisted = committed.stream().map(p -> p.getInstrument().getId()).collect(Collectors.toSet());
        assertThat(persisted).containsExactlyInAnyOrder(first.getId(), third.getId());
        assertThat(result.refreshed()).isEqualTo(2);
        assertThat(result.refreshedItems()).extracting(PriceRefreshResult.RefreshedItem::instrumentId)
                .containsExactlyInAnyOrder(first.getId(), third.getId());
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).instrumentId()).isEqualTo(second.getId());
        assertThat(result.failed().get(0).reason()).contains("ORA-02290");
    }

    @Test
    void theFailedInstrumentIsLoggedAsOneStructuredIngestItemFailedWarn() {
        service.refresh(Optional.empty());

        List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        String rendered = warns.get(0).getFormattedMessage() + " " + Arrays.toString(warns.get(0).getArgumentArray());
        assertThat(rendered).contains("event=ingest.item_failed", "jobName=price-refresh", "itemType=instrument",
                "itemId=" + second.getId(), "symbol=TCS", "errorClass=SQLException", "attempt=1", "status=FAILED",
                "ORA-02290");
    }

    @Test
    void whenNothingFailsNoWarnIsLoggedAndAllArePersisted() {
        doomedId = UUID.randomUUID();

        PriceRefreshResult result = service.refresh(Optional.empty());

        assertThat(committed).hasSize(3);
        assertThat(result.failed()).isEmpty();
        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).isEmpty();
    }
}
