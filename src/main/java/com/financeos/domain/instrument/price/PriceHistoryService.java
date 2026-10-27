package com.financeos.domain.instrument.price;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

/**
 * Loads daily price history for instruments a trade touched, and prunes old feed prices monthly.
 * Window per instrument: start = the earlier of (oldest trade by any user - 1 year) and (today - 2 years),
 * end = yesterday. Each trigger fetches the whole window in one request and upserts shared feed rows only
 * (user_id NULL): missing dates are inserted, existing feed rows get the fetched close, never a user's
 * MANUAL row and never today (live and intraday rows own today).
 */
@Service
public class PriceHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryService.class);

    /** An instrument fetched successfully for the same or an earlier start within this time is not fetched again. */
    static final Duration DEBOUNCE = Duration.ofHours(6);
    static final int UPSERT_BATCH_SIZE = 500;
    static final int CLEANUP_CHUNK = 20_000;

    private static final String UPSERT_SQL = "MERGE INTO instrument_prices t "
            + "USING (SELECT ? AS instrument_id, ? AS as_of, ? AS close, ? AS source FROM dual) s "
            + "ON (t.instrument_id = s.instrument_id AND t.as_of = s.as_of AND t.user_id IS NULL) "
            + "WHEN MATCHED THEN UPDATE SET t.close = s.close, t.source = s.source, t.price_time = NULL "
            + "WHERE t.source <> 'MANUAL' "
            + "WHEN NOT MATCHED THEN INSERT (id, instrument_id, as_of, close, source, created_at) "
            + "VALUES (?, s.instrument_id, s.as_of, s.close, s.source, CURRENT_TIMESTAMP)";

    private record Fetched(LocalDate start, Instant at) {}

    private final PriceProperties priceProperties;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final PriceHistoryClient historyClient;
    private final JdbcTemplate jdbcTemplate;
    private final Map<UUID, Fetched> lastFetched = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public PriceHistoryService(PriceProperties priceProperties, InstrumentRepository instrumentRepository,
                               InstrumentPriceRepository priceRepository,
                               InvestmentTransactionRepository transactionRepository,
                               PriceHistoryClient historyClient, JdbcTemplate jdbcTemplate) {
        this.priceProperties = priceProperties;
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.transactionRepository = transactionRepository;
        this.historyClient = historyClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** For tests: the debounce clock. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Start of the history window: the earlier of {@code earliestTrade - yearsBeforeFirstTrade} and
     * {@code today - minYears}; with no trade, {@code today - minYears}.
     */
    static LocalDate windowStart(LocalDate today, LocalDate earliestTrade, int minYears, int yearsBeforeFirstTrade) {
        LocalDate minReach = today.minusYears(minYears);
        if (earliestTrade == null) {
            return minReach;
        }
        LocalDate beforeTrade = earliestTrade.minusYears(yearsBeforeFirstTrade);
        return beforeTrade.isBefore(minReach) ? beforeTrade : minReach;
    }

    /** Fetches and upserts each instrument's window independently; never throws. Returns the rows written. */
    public int backfill(Collection<UUID> instrumentIds) {
        PriceProperties.History cfg = priceProperties.getHistory();
        if (!cfg.isEnabled() || instrumentIds == null || instrumentIds.isEmpty()) {
            return 0;
        }
        int written = 0;
        try {
            List<Instrument> instruments = instrumentRepository.findAllById(instrumentIds);
            Map<UUID, LocalDate> earliestTrade = toDateMap(transactionRepository.findEarliestTradeDates(instrumentIds));
            LocalDate today = AppTime.today();
            for (Instrument instrument : instruments) {
                try {
                    written += backfillOne(instrument, today, earliestTrade.get(instrument.getId()), cfg);
                } catch (Exception e) {
                    log.warn("Price history backfill failed for instrument {}: {}", instrument.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Price history backfill failed: {}", e.getMessage());
        }
        return written;
    }

    private int backfillOne(Instrument instrument, LocalDate today, LocalDate firstTrade, PriceProperties.History cfg) {
        boolean yahoo = instrument.getYahooSymbol() != null && !instrument.getYahooSymbol().isBlank();
        boolean amfi = instrument.getAmfiCode() != null && !instrument.getAmfiCode().isBlank();
        if (!yahoo && !amfi) {
            return 0;
        }
        LocalDate from = windowStart(today, firstTrade, cfg.getMinYears(), cfg.getYearsBeforeFirstTrade());
        LocalDate to = today.minusDays(1);
        Fetched last = lastFetched.get(instrument.getId());
        if (last != null && !last.start().isAfter(from) && clock.instant().isBefore(last.at().plus(DEBOUNCE))) {
            return 0;
        }
        PriceHistoryClient.Result result;
        PriceSource source;
        if (yahoo) {
            source = PriceSource.YAHOO;
            result = historyClient.fetchYahoo(instrument.getYahooSymbol(), from, to);
        } else {
            source = PriceSource.AMFI;
            result = historyClient.fetchMutualFund(instrument.getAmfiCode(), from, to);
        }
        if (result.status() != PriceHistoryClient.Status.OK) {
            log.info("Price history for instrument {} not loaded ({}); a later trade will retry", instrument.getId(), result.status());
            return 0;
        }
        Map<LocalDate, PriceHistoryClient.DailyClose> rows = new TreeMap<>();
        for (PriceHistoryClient.DailyClose c : result.closes()) {
            if (c.date().isBefore(from) || c.date().isAfter(to)) {
                continue;
            }
            rows.putIfAbsent(c.date(), c);
        }
        int n = upsert(instrument.getId(), source, new ArrayList<>(rows.values()));
        lastFetched.put(instrument.getId(), new Fetched(from, clock.instant()));
        log.info("Price history for instrument {}: {} rows upserted ({} to {})", instrument.getId(), n, from, to);
        return n;
    }

    /**
     * Batched MERGE into the shared feed rows: inserts a missing date, overwrites close/source and clears
     * price_time on an existing feed row, never touches a MANUAL or user row. A unique-violation race is ignored.
     */
    int upsert(UUID instrumentId, PriceSource source, List<PriceHistoryClient.DailyClose> closes) {
        int written = 0;
        for (int i = 0; i < closes.size(); i += UPSERT_BATCH_SIZE) {
            List<PriceHistoryClient.DailyClose> chunk = closes.subList(i, Math.min(i + UPSERT_BATCH_SIZE, closes.size()));
            try {
                int[][] batches = jdbcTemplate.batchUpdate(UPSERT_SQL, chunk, chunk.size(), (ps, c) -> {
                    ps.setString(1, instrumentId.toString());
                    ps.setDate(2, java.sql.Date.valueOf(c.date()));
                    ps.setBigDecimal(3, c.close());
                    ps.setString(4, source.name());
                    ps.setString(5, UUID.randomUUID().toString());
                });
                int[] counts = batches.length > 0 ? batches[0] : new int[0];
                for (int count : counts) {
                    // Oracle may report SUCCESS_NO_INFO for a batched statement: count it as written.
                    written += count > 0 ? count : (count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : 0);
                }
            } catch (DuplicateKeyException e) {
                log.info("Price history upsert for {} raced with another writer; ignored", instrumentId);
            }
        }
        return written;
    }

    /**
     * Monthly prune of feed rows (never user/MANUAL rows, every instrument keeps its newest feed row):
     * instruments with a trade lose rows older than their window start; instruments without one keep only
     * their newest. Returns the rows deleted.
     */
    public int cleanup() {
        PriceProperties.History cfg = priceProperties.getHistory();
        LocalDate minStart = AppTime.today().minusYears(cfg.getMinYears());
        int months = 12 * cfg.getYearsBeforeFirstTrade();
        int total = 0;
        int deleted;
        do {
            deleted = priceRepository.deleteFeedPricesBeforeWindow(minStart, months, CLEANUP_CHUNK);
            total += deleted;
        } while (deleted >= CLEANUP_CHUNK);
        int traded = total;
        do {
            deleted = priceRepository.deleteFeedPricesOfUntradedInstruments(CLEANUP_CHUNK);
            total += deleted;
        } while (deleted >= CLEANUP_CHUNK);
        log.info("Price history cleanup deleted {} feed prices of traded instruments and {} of untraded ones",
                traded, total - traded);
        return total;
    }

    private static Map<UUID, LocalDate> toDateMap(List<Object[]> rows) {
        Map<UUID, LocalDate> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(UUID.fromString(String.valueOf(row[0])), toLocalDate(row[1]));
        }
        return map;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }
}
