package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.corporateaction.detect.NseCorporateActionsClient.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The structural corporate events read from the exchange, kept in {@code market_corporate_events}, and how far back
 * they are loaded ({@code market_event_coverage}). Fetches run outside any transaction; only the writes commit.
 */
@Service
public class MarketEventStore {

    private static final Logger log = LoggerFactory.getLogger(MarketEventStore.class);
    private static final String SOURCE = MarketCorporateEvent.SOURCE_NSE;

    private final NseCorporateActionsClient client;
    private final MarketCorporateEventRepository events;
    private final MarketEventCoverageRepository coverage;
    /** Pause between the per-year fetches of a back-fill (the site throttles bursts). */
    private long pauseMs = 1000;

    public MarketEventStore(NseCorporateActionsClient client, MarketCorporateEventRepository events,
                            MarketEventCoverageRepository coverage) {
        this.client = client;
        this.events = events;
        this.coverage = coverage;
    }

    /** Test hook: no pause between back-fill fetches. */
    void setPauseMs(long pauseMs) {
        this.pauseMs = pauseMs;
    }

    /** Fetches [from, to] and stores the structural equity rows not stored yet; false when the fetch failed. */
    public boolean refreshWindow(LocalDate from, LocalDate to) {
        NseCorporateActionsClient.Result result = client.fetchWindow(from, to);
        if (result.status() != NseCorporateActionsClient.Status.OK) {
            return false;
        }
        Set<String> known = new HashSet<>();
        for (MarketCorporateEvent e : events.findWindow(SOURCE, from, to)) {
            known.add(key(e.getSymbol(), e.getExDate(), cut(e.getSubject() == null ? "" : e.getSubject(), 500)));
        }
        Instant now = AppTime.nowInstant();
        List<MarketCorporateEvent> fresh = new ArrayList<>();
        int bad = 0;
        for (Row row : result.rows()) {
            try {
                MarketCorporateEvent e = toEvent(row, known, now);
                if (e != null) {
                    fresh.add(e);
                }
            } catch (RuntimeException ex) {
                bad++;
            }
        }
        if (bad > 0) {
            log.warn("Corporate events {}..{}: {} rows could not be read and were skipped", from, to, bad);
        }
        if (!fresh.isEmpty()) {
            try {
                events.saveAll(fresh);
            } catch (DataIntegrityViolationException dup) {
                for (MarketCorporateEvent e : fresh) {
                    try {
                        events.save(e);
                    } catch (DataIntegrityViolationException ignored) {
                        // stored meanwhile by a concurrent writer
                    }
                }
            }
        }
        log.info("Corporate events {}..{}: {} rows read, {} new structural", from, to, result.rows().size(), fresh.size());
        return true;
    }

    /** The event to store for {@code row}, or null when the row is not a new structural equity row. */
    private MarketCorporateEvent toEvent(Row row, Set<String> known, Instant now) {
        if (row.symbol() == null || row.exDate() == null || row.subject() == null || row.subject().isBlank()
                || (row.series() != null && !row.series().equalsIgnoreCase("EQ"))) {
            return null;
        }
        NseSubjectParser.Parsed parsed = NseSubjectParser.parse(row.subject());
        if (parsed == null) {
            return null;
        }
        String symbol = row.symbol().trim().toUpperCase(Locale.ROOT);
        String subject = cut(row.subject(), 500);
        if (!known.add(key(symbol, row.exDate(), subject))) {
            return null;
        }
        MarketCorporateEvent e = new MarketCorporateEvent();
        e.setSource(SOURCE);
        e.setSymbol(symbol);
        e.setIsin(row.isin());
        e.setCompany(row.company() == null ? null : cut(row.company(), 200));
        e.setSeries(row.series());
        e.setExDate(row.exDate());
        e.setSubject(subject);
        e.setParsedType(parsed.type().name());
        e.setRatioFrom(parsed.ratioFrom());
        e.setRatioTo(parsed.ratioTo());
        e.setClean(parsed.clean());
        e.setFetchedAt(now);
        return e;
    }

    /**
     * Makes sure events back to {@code neededFrom} are loaded. First use loads the last 30 days; an older need loads
     * the gap one calendar year per call, newest year first, and stops at the first failure.
     */
    public void ensureCoverage(LocalDate neededFrom) {
        LocalDate today = AppTime.today();
        MarketEventCoverage cov = coverage.findById(SOURCE).orElse(null);
        if (cov == null || cov.getCoveredFrom() == null) {
            LocalDate from = today.minusDays(30);
            if (!refreshWindow(from, today.plusDays(1))) {
                return;
            }
            cov = save(cov, from, today);
        }
        LocalDate coveredFrom = cov.getCoveredFrom();
        if (!neededFrom.isBefore(coveredFrom)) {
            return;
        }
        LocalDate start = LocalDate.of(neededFrom.getYear(), 1, 1);
        LocalDate to = coveredFrom.minusDays(1);
        boolean first = true;
        while (!to.isBefore(start)) {
            LocalDate from = LocalDate.of(to.getYear(), 1, 1);
            if (from.isBefore(start)) {
                from = start;
            }
            if (!first) {
                pause();
            }
            first = false;
            if (!refreshWindow(from, to)) {
                return;
            }
            cov.setCoveredFrom(from);
            cov.setUpdatedAt(AppTime.nowInstant());
            cov = coverage.save(cov);
            to = from.minusDays(1);
        }
    }

    /** Re-reads the last week up to tomorrow and moves the covered end to today. */
    public boolean refreshRecent() {
        LocalDate today = AppTime.today();
        MarketEventCoverage cov = coverage.findById(SOURCE).orElse(null);
        LocalDate from = cov != null && cov.getCoveredTo() != null ? cov.getCoveredTo().minusDays(7) : today.minusDays(30);
        if (!refreshWindow(from, today.plusDays(1))) {
            return false;
        }
        if (cov == null || cov.getCoveredFrom() == null) {
            save(cov, from, today);
        } else {
            cov.setCoveredTo(today);
            cov.setUpdatedAt(AppTime.nowInstant());
            coverage.save(cov);
        }
        return true;
    }

    private MarketEventCoverage save(MarketEventCoverage existing, LocalDate from, LocalDate to) {
        MarketEventCoverage cov = existing != null ? existing : new MarketEventCoverage();
        cov.setSource(SOURCE);
        cov.setCoveredFrom(from);
        cov.setCoveredTo(to);
        cov.setUpdatedAt(AppTime.nowInstant());
        return coverage.save(cov);
    }

    private void pause() {
        if (pauseMs <= 0) {
            return;
        }
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String key(String symbol, LocalDate exDate, String subject) {
        return symbol + "|" + exDate + "|" + subject;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
