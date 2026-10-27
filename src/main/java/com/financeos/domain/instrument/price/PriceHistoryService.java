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
import java.time.LocalDate;
import java.util.*;

/**
 * Loads daily price history for an instrument that has just become held, and prunes old feed
 * prices monthly. Inserts shared feed rows only (user_id NULL, no price_time), never overwriting a
 * date that already has one, and never for today (live and intraday rows own today).
 */
@Service
public class PriceHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryService.class);

    /** Feed coverage counts as reaching {@code from} when its oldest row is within this many days of it (holidays, weekends). */
    static final int COVERAGE_TOLERANCE_DAYS = 5;
    static final int INSERT_BATCH_SIZE = 500;
    static final int CLEANUP_CHUNK = 20_000;

    private static final String INSERT_SQL = "INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) "
            + "SELECT ?, ?, ?, ?, ?, CURRENT_TIMESTAMP FROM dual WHERE NOT EXISTS ("
            + "SELECT 1 FROM instrument_prices x WHERE x.instrument_id = ? AND x.as_of = ? AND x.user_id IS NULL)";

    private final PriceProperties priceProperties;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final PriceHistoryClient historyClient;
    private final JdbcTemplate jdbcTemplate;

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

    /**
     * Start of the history window: {@code max(today - maxYears, min(earliestTrade, today - minYears))};
     * with no trade date, {@code today - minYears}.
     */
    static LocalDate windowStart(LocalDate today, LocalDate earliestTrade, int maxYears, int minYears) {
        LocalDate floor = today.minusYears(maxYears);
        LocalDate minReach = today.minusYears(minYears);
        LocalDate wanted = earliestTrade == null || earliestTrade.isAfter(minReach) ? minReach : earliestTrade;
        return wanted.isBefore(floor) ? floor : wanted;
    }

    /** What to fetch given the feed's oldest row: null when {@code from} is already covered, else the missing span's last day. */
    static LocalDate missingSpanEnd(LocalDate from, LocalDate yesterday, LocalDate earliestFeed) {
        if (earliestFeed == null) {
            return yesterday;
        }
        if (!earliestFeed.isAfter(from.plusDays(COVERAGE_TOLERANCE_DAYS))) {
            return null;
        }
        LocalDate end = earliestFeed.minusDays(1);
        return end.isAfter(yesterday) ? yesterday : end;
    }

    /** Backfills each instrument independently; never throws. Returns the rows inserted. */
    public int backfill(Collection<UUID> instrumentIds) {
        PriceProperties.History cfg = priceProperties.getHistory();
        if (!cfg.isEnabled() || instrumentIds == null || instrumentIds.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        try {
            List<Instrument> instruments = instrumentRepository.findAllById(instrumentIds);
            Map<UUID, LocalDate> earliestTrade = toDateMap(transactionRepository.findEarliestTradeDates(instrumentIds));
            Map<UUID, LocalDate> earliestFeed = toDateMap(priceRepository.findEarliestFeedAsOf(instrumentIds));
            LocalDate today = AppTime.today();
            for (Instrument instrument : instruments) {
                try {
                    inserted += backfillOne(instrument, today, earliestTrade.get(instrument.getId()),
                            earliestFeed.get(instrument.getId()), cfg);
                } catch (Exception e) {
                    log.warn("Price history backfill failed for instrument {}: {}", instrument.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Price history backfill failed: {}", e.getMessage());
        }
        return inserted;
    }

    private int backfillOne(Instrument instrument, LocalDate today, LocalDate firstTrade, LocalDate firstFeed,
                            PriceProperties.History cfg) {
        boolean yahoo = instrument.getYahooSymbol() != null && !instrument.getYahooSymbol().isBlank();
        boolean amfi = instrument.getAmfiCode() != null && !instrument.getAmfiCode().isBlank();
        if (!yahoo && !amfi) {
            return 0;
        }
        LocalDate from = windowStart(today, firstTrade, cfg.getMaxYears(), cfg.getMinYears());
        LocalDate to = missingSpanEnd(from, today.minusDays(1), firstFeed);
        if (to == null || to.isBefore(from)) {
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
        Set<LocalDate> existing = new HashSet<>(priceRepository.findFeedDatesBetween(instrument.getId(), from, to));
        Map<LocalDate, PriceHistoryClient.DailyClose> fresh = new TreeMap<>();
        for (PriceHistoryClient.DailyClose c : result.closes()) {
            if (c.date().isBefore(from) || c.date().isAfter(to) || existing.contains(c.date())) {
                continue;
            }
            fresh.putIfAbsent(c.date(), c);
        }
        int n = insert(instrument.getId(), source, new ArrayList<>(fresh.values()));
        log.info("Price history backfill for instrument {}: {} rows ({} to {})", instrument.getId(), n, from, to);
        return n;
    }

    /** Batched insert; the SQL itself skips a date that already has a feed row, a unique-violation race is ignored. */
    int insert(UUID instrumentId, PriceSource source, List<PriceHistoryClient.DailyClose> closes) {
        int inserted = 0;
        for (int i = 0; i < closes.size(); i += INSERT_BATCH_SIZE) {
            List<PriceHistoryClient.DailyClose> chunk = closes.subList(i, Math.min(i + INSERT_BATCH_SIZE, closes.size()));
            try {
                int[][] batches = jdbcTemplate.batchUpdate(INSERT_SQL, chunk, chunk.size(), (ps, c) -> {
                    java.sql.Date date = java.sql.Date.valueOf(c.date());
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, instrumentId.toString());
                    ps.setDate(3, date);
                    ps.setBigDecimal(4, c.close());
                    ps.setString(5, source.name());
                    ps.setString(6, instrumentId.toString());
                    ps.setDate(7, date);
                });
                int[] counts = batches.length > 0 ? batches[0] : new int[0];
                for (int count : counts) {
                    // Oracle may report SUCCESS_NO_INFO for a batched statement: count it as inserted.
                    inserted += count > 0 ? count : (count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : 0);
                }
            } catch (DuplicateKeyException e) {
                log.info("Price history insert for {} raced with another writer; ignored", instrumentId);
            }
        }
        return inserted;
    }

    /** Monthly prune: feed rows older than {@code today - maxYears}, keeping each instrument's newest. Returns the rows deleted. */
    public int cleanup() {
        LocalDate cutoff = AppTime.today().minusYears(priceProperties.getHistory().getMaxYears());
        int total = 0;
        int deleted;
        do {
            deleted = priceRepository.deleteStaleFeedPrices(cutoff, CLEANUP_CHUNK);
            total += deleted;
        } while (deleted >= CLEANUP_CHUNK);
        log.info("Price history cleanup deleted {} feed prices older than {}", total, cutoff);
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
