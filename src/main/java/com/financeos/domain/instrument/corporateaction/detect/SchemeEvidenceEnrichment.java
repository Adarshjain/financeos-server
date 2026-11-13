package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.corporateaction.detect.NseAnnouncementsClient.Notice;
import com.financeos.domain.instrument.corporateaction.detect.NseListingsClient.Listing;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.Child;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.Kind;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.NoticeText;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.Reading;
import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.Source;
import com.financeos.domain.instrument.price.PriceHistoryClient;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.job.JobCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The worker's reading of the company's own exchange notices for the demergers users have pending and the merged or
 * no-longer-trading stocks they hold. One reading per (stock, ex-date) for all users; an incomplete one is read again
 * daily until 200 days after the event, then weekly up to a year. Network work happens outside any transaction; only
 * the result is saved in one short one. Never throws out of {@link #run}.
 */
@Service
public class SchemeEvidenceEnrichment {

    private static final Logger log = LoggerFactory.getLogger(SchemeEvidenceEnrichment.class);

    static final int MAX_EVENTS_PER_RUN = 25;
    static final int MAX_EVENTS_PER_USER_RUN = 10;
    static final int MAX_DOCUMENTS = 12;
    static final int DEMERGER_BACK_DAYS = 730;
    static final int MERGER_BACK_DAYS = 400;
    static final int MERGER_FORWARD_DAYS = 60;
    static final int DAILY_UNTIL_DAYS = 200;
    /** Weekly after that, until the child-listing window closes. */
    static final int WEEKLY_UNTIL_DAYS = SchemeNoticeReader.CHILD_WINDOW_DAYS;
    static final double ESTIMATE_MIN = 1;
    static final double ESTIMATE_MAX = 99;

    private static final Pattern WORTH_READING = Pattern.compile(
            "demerg|arrangement|amalgamat|merger|\\bmerge\\b|record date|entitlement|cost of acquisition|apportion|resulting company|scheme|nclt|transferee",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIVIDEND = Pattern.compile("dividend", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOP = Pattern.compile("record date|cost of acquisition|apportion|entitlement", Pattern.CASE_INSENSITIVE);
    private static final Pattern MID = Pattern.compile("demerg|amalgamat|arrangement|merger", Pattern.CASE_INSENSITIVE);

    /** One stock and event to read for. {@code exDate} is null for a merger; {@code lastTrade} anchors a merger's window. */
    public record Target(Kind kind, String symbol, @Nullable String isin, @Nullable String name, @Nullable String yahooSymbol,
                         @Nullable LocalDate exDate, @Nullable LocalDate lastTrade) {
        LocalDate anchor() {
            return exDate != null ? exDate : lastTrade;
        }
    }

    /** What a run did. */
    public record Summary(int due, int read, int complete) {
    }

    private final CorporateActionSuggestionRepository suggestions;
    private final InvestmentTransactionRepository transactions;
    private final InstrumentPriceRepository prices;
    private final SchemeEvidenceRepository evidence;
    private final SchemeEvidenceChildRepository children;
    private final NseAnnouncementsClient announcements;
    private final NseListingsClient listings;
    private final NseNameChangesClient nameChanges;
    private final PriceHistoryClient priceHistory;
    private final TransactionTemplate tx;

    public SchemeEvidenceEnrichment(CorporateActionSuggestionRepository suggestions, InvestmentTransactionRepository transactions,
                                    InstrumentPriceRepository prices, SchemeEvidenceRepository evidence,
                                    SchemeEvidenceChildRepository children, NseAnnouncementsClient announcements,
                                    NseListingsClient listings, NseNameChangesClient nameChanges, PriceHistoryClient priceHistory,
                                    TransactionTemplate tx) {
        this.suggestions = suggestions;
        this.transactions = transactions;
        this.prices = prices;
        this.evidence = evidence;
        this.children = children;
        this.announcements = announcements;
        this.listings = listings;
        this.nameChanges = nameChanges;
        this.priceHistory = priceHistory;
        this.tx = tx;
    }

    /** Reads what is due (at most 25 events); {@code checkpoint} runs before each event (job cancellation). */
    public Summary run(Runnable checkpoint) {
        return process(() -> gather(), MAX_EVENTS_PER_RUN, checkpoint);
    }

    /**
     * As {@link #run} for one user's own pending demergers and stopped stocks, at most 10 events and only those due
     * (incomplete readings wait for their next check): the per-user detection job after an import.
     */
    public Summary runForUser(UUID userId, Runnable checkpoint) {
        return process(() -> gatherFor(userId), MAX_EVENTS_PER_USER_RUN, checkpoint);
    }

    private Summary process(java.util.function.Supplier<List<Target>> gather, int cap, Runnable checkpoint) {
        int due = 0;
        int read = 0;
        int complete = 0;
        try {
            Instant now = AppTime.nowInstant();
            List<Target> due0 = dueTargets(gather.get(), now);
            due = due0.size();
            if (listings.listings().isEmpty()) {
                log.info("Scheme notice reading skipped: the exchange's listing list is not loaded yet ({} due)", due);
                return new Summary(due, 0, 0);
            }
            for (Target t : due0.subList(0, Math.min(cap, due0.size()))) {
                checkpoint.run();
                try {
                    Boolean done = enrich(t);
                    if (done != null) {
                        read++;
                        if (done) {
                            complete++;
                        }
                    }
                } catch (RuntimeException e) {
                    if (e instanceof JobCancelledException) {
                        throw e;
                    }
                    log.warn("Scheme notice reading failed for {} {}: {}", t.symbol(), t.anchor(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            if (e instanceof JobCancelledException) {
                throw e;
            }
            log.warn("Scheme notice reading failed: {}", e.toString());
        }
        log.info("Scheme notice reading: due={} read={} complete={}", due, read, complete);
        return new Summary(due, read, complete);
    }

    /** Distinct pending demergers (every user), then the not-trading stocks anyone traded. */
    List<Target> gather() {
        return targets(suggestions.findAllPendingOfKind(SuggestionKind.demerger), transactions.findNotTradingInstrumentsTradedByAnyone());
    }

    /** The same for one user's own pending demergers and the not-trading stocks they traded. */
    List<Target> gatherFor(UUID userId) {
        List<CorporateActionSuggestion> pending = suggestions.findPendingUnresolved(userId).stream()
                .filter(s -> s.getKind() == SuggestionKind.demerger).toList();
        return targets(pending, transactions.findNotTradingInstrumentsTradedBy(userId));
    }

    private List<Target> targets(List<CorporateActionSuggestion> pending, List<Instrument> stopped) {
        Map<String, Target> out = new LinkedHashMap<>();
        for (CorporateActionSuggestion s : pending) {
            Instrument i = s.getInstrument();
            if (i.getSymbol() == null || i.getSymbol().isBlank()) {
                continue;
            }
            String symbol = i.getSymbol().trim().toUpperCase(Locale.ROOT);
            out.putIfAbsent(symbol + "|" + s.getExDate(), new Target(Kind.demerger, symbol, i.getIsin(), i.getName(), i.getYahooSymbol(),
                    s.getExDate(), null));
        }
        if (!stopped.isEmpty()) {
            Map<UUID, LocalDate> lastPrice = new HashMap<>();
            for (InstrumentPrice p : prices.findLatestFeedRows(stopped.stream().map(Instrument::getId).toList())) {
                lastPrice.merge(p.getInstrument().getId(), p.getAsOf(), (a, b) -> a.isAfter(b) ? a : b);
            }
            for (Instrument i : stopped) {
                if (i.getSymbol() == null || i.getSymbol().isBlank()) {
                    continue;
                }
                String symbol = i.getSymbol().trim().toUpperCase(Locale.ROOT);
                LocalDate last = lastPrice.get(i.getId());
                if (last == null && i.getPriceStatusAt() != null) {
                    last = i.getPriceStatusAt().atZone(AppTime.zone()).toLocalDate();
                }
                if (last == null) {
                    continue;
                }
                out.putIfAbsent(symbol + "|merger", new Target(Kind.merger, symbol, i.getIsin(), i.getName(), i.getYahooSymbol(), null, last));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** The targets whose evidence is missing or incomplete with its next check passed, in the order given. */
    List<Target> dueTargets(List<Target> all, Instant now) {
        List<Target> due = new ArrayList<>();
        for (Target t : all) {
            SchemeEvidence e = evidence.findScheme(t.symbol(), t.kind().name(), t.exDate()).orElse(null);
            if (e == null || (!e.isComplete() && e.getNextCheckAt() != null && !e.getNextCheckAt().isAfter(now))) {
                due.add(t);
            }
        }
        return due;
    }

    /** True when the reading is complete, false when it is saved incomplete, null when nothing could be read or saved. */
    Boolean enrich(Target t) {
        List<Listing> all = listings.listings();
        LocalDate today = AppTime.today();
        Set<String> symbols = new LinkedHashSet<>();
        symbols.add(t.symbol());
        String current = currentSymbol(all, t.isin());
        if (current != null) {
            symbols.add(current.toUpperCase(Locale.ROOT));
        }
        LocalDate anchor = t.anchor();
        LocalDate from = t.kind() == Kind.demerger ? anchor.minusDays(DEMERGER_BACK_DAYS) : anchor.minusDays(MERGER_BACK_DAYS);
        LocalDate to = t.kind() == Kind.demerger ? anchor.plusDays(SchemeNoticeReader.CHILD_WINDOW_DAYS) : anchor.plusDays(MERGER_FORWARD_DAYS);
        if (to.isAfter(today)) {
            to = today;
        }
        NseAnnouncementsClient.Listed listed = announcements.list(symbols, from, to);
        List<Notice> worth = worthReading(listed.notices());
        if (listed.failed() && listed.notices().isEmpty()) {
            return saveUnavailable(t, today);
        }
        List<NoticeText> texts = new ArrayList<>();
        int fetched = 0;
        for (Notice n : worth) {
            String pdf = null;
            boolean scanned = false;
            if (n.fileUrl() != null && n.fileUrl().toLowerCase(Locale.ROOT).endsWith(".pdf") && fetched < MAX_DOCUMENTS) {
                fetched++;
                NseAnnouncementsClient.Document d = announcements.fetchDocument(n.fileUrl());
                if (d.ok()) {
                    pdf = d.text();
                    scanned = d.scanned();
                }
            }
            texts.add(new NoticeText(n.fileUrl() == null ? "" : n.fileUrl(), n.date(), n.subject() + ". " + n.summary(), pdf, scanned));
        }
        Reading reading = SchemeNoticeReader.read(t.kind(), t.name(), t.symbol(), t.isin(), t.exDate(), texts, all, nameChanges.nameChanges());
        List<String> missing = new ArrayList<>(reading.missing());
        if (listed.failed() && !missing.contains(SchemeNoticeReader.NOTICES_UNAVAILABLE)) {
            missing.add(SchemeNoticeReader.NOTICES_UNAVAILABLE);
        }
        BigDecimal parentPct = reading.parentCostPct();
        String costKind = parentPct == null ? null : SchemeEvidence.COST_NOTICE;
        Map<Listing, BigDecimal> childPct = new HashMap<>();
        for (Child c : reading.children()) {
            if (c.costPct() != null) {
                childPct.put(c.listing(), c.costPct());
            }
        }
        if (parentPct == null && t.kind() == Kind.demerger && reading.children().size() == 1 && t.exDate() != null) {
            BigDecimal est = estimate(t, current);
            if (est != null) {
                parentPct = est;
                costKind = SchemeEvidence.COST_ESTIMATE;
                childPct.put(reading.children().get(0).listing(), BigDecimal.valueOf(100).subtract(est));
            }
        }
        boolean complete = t.kind() == Kind.demerger
                ? SchemeEvidence.COST_NOTICE.equals(costKind) && !reading.children().isEmpty() && reading.children().stream().allMatch(c -> c.ratioFrom() != null)
                : !reading.children().isEmpty() && reading.children().stream().allMatch(c -> c.ratioFrom() != null);
        save(t, reading, parentPct, costKind, childPct, missing, complete, today);
        return complete;
    }

    private Boolean saveUnavailable(Target t, LocalDate today) {
        tx.executeWithoutResult(status -> {
            SchemeEvidence e = evidence.findScheme(t.symbol(), t.kind().name(), t.exDate()).orElseGet(() -> fresh(t));
            Instant now = AppTime.nowInstant();
            if (e.getId() == null) {
                e.setMissing(SchemeNoticeReader.NOTICES_UNAVAILABLE);
            }
            e.setCheckedAt(now);
            e.setNextCheckAt(nextCheck(t, false, today, now));
            evidence.save(e);
        });
        return null;
    }

    private static SchemeEvidence fresh(Target t) {
        SchemeEvidence e = new SchemeEvidence();
        e.setParentSymbol(t.symbol());
        e.setParentIsin(t.isin());
        e.setKind(t.kind().name());
        e.setExDate(t.exDate());
        return e;
    }

    private void save(Target t, Reading r, @Nullable BigDecimal parentPct, @Nullable String costKind, Map<Listing, BigDecimal> childPct,
                      List<String> missing, boolean complete, LocalDate today) {
        tx.executeWithoutResult(status -> {
            SchemeEvidence e = evidence.findScheme(t.symbol(), t.kind().name(), t.exDate()).orElseGet(() -> fresh(t));
            Instant now = AppTime.nowInstant();
            e.setParentIsin(t.isin());
            e.setRecordDate(r.recordDate());
            e.setParentCostPct(parentPct);
            e.setCostKind(costKind);
            Source cs = r.costSource();
            boolean notice = SchemeEvidence.COST_NOTICE.equals(costKind) && cs != null;
            e.setCostUrl(notice ? cs.url() : null);
            e.setCostNoticeDate(notice ? cs.date() : null);
            e.setCostQuote(notice ? cut(cs.quote()) : null);
            e.setMissing(cut(String.join(",", missing)));
            e.setComplete(complete);
            e.setCheckedAt(now);
            e.setNextCheckAt(nextCheck(t, complete, today, now));
            SchemeEvidence saved = evidence.saveAndFlush(e);
            children.deleteForEvidence(saved.getId());
            int order = 0;
            for (Child c : r.children()) {
                SchemeEvidenceChild row = new SchemeEvidenceChild();
                row.setEvidenceId(saved.getId());
                row.setChildOrder(order++);
                row.setChildSymbol(c.listing().symbol());
                row.setChildIsin(c.listing().isin());
                row.setChildName(c.listing().name());
                row.setListedOn(c.listing().listedOn());
                row.setRatioFrom(c.ratioFrom());
                row.setRatioTo(c.ratioTo());
                if (c.ratioSource() != null) {
                    row.setRatioUrl(c.ratioSource().url());
                    row.setRatioNoticeDate(c.ratioSource().date());
                    row.setRatioQuote(cut(c.ratioSource().quote()));
                }
                row.setChildCostPct(childPct.get(c.listing()));
                children.save(row);
            }
        });
    }

    /** Daily until 200 days after the event, then weekly up to a year, then never; never for a complete reading. */
    static Instant nextCheck(Target t, boolean complete, LocalDate today, Instant now) {
        if (complete || t.anchor() == null) {
            return null;
        }
        long age = ChronoUnit.DAYS.between(t.anchor(), today);
        if (age < DAILY_UNTIL_DAYS) {
            return now.plus(1, ChronoUnit.DAYS);
        }
        if (age < WEEKLY_UNTIL_DAYS) {
            return now.plus(7, ChronoUnit.DAYS);
        }
        return null;
    }

    private static String cut(String s) {
        return s == null ? null : s.length() <= 300 ? s : s.substring(0, 300);
    }

    /** The notices that speak of a scheme, no dividends; those most likely to hold the numbers first, then newest. */
    static List<Notice> worthReading(List<Notice> all) {
        return all.stream()
                .filter(n -> {
                    String text = n.subject() + " " + n.summary();
                    return WORTH_READING.matcher(text).find() && !DIVIDEND.matcher(text).find();
                })
                .sorted(Comparator.comparingInt((Notice n) -> -score(n))
                        .thenComparing(Notice::date, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    private static int score(Notice n) {
        String text = n.subject() + " " + n.summary();
        return TOP.matcher(text).find() ? 3 : MID.matcher(text).find() ? 2 : 1;
    }

    private static String currentSymbol(List<Listing> all, @Nullable String isin) {
        if (isin == null) {
            return null;
        }
        for (Listing l : all) {
            if (l.isin() != null && l.isin().equalsIgnoreCase(isin.trim())) {
                return l.symbol();
            }
        }
        return null;
    }

    /**
     * The parent's share of the cost from the prices around the ex-date: its close on the ex-date over the close of the
     * previous trading day, as a percentage with two decimals, kept within 1..99. Null when the price feed has no such pair.
     */
    @Nullable
    private BigDecimal estimate(Target t, @Nullable String currentSymbol) {
        List<String> symbols = new ArrayList<>();
        if (t.yahooSymbol() != null && !t.yahooSymbol().isBlank()) {
            symbols.add(t.yahooSymbol());
        }
        symbols.add(t.symbol() + ".NS");
        if (currentSymbol != null) {
            symbols.add(currentSymbol + ".NS");
        }
        for (String symbol : symbols.stream().distinct().toList()) {
            PriceHistoryClient.Result r = priceHistory.fetchYahoo(symbol, t.exDate().minusDays(14), t.exDate());
            if (r.status() != PriceHistoryClient.Status.OK) {
                continue;
            }
            BigDecimal on = null;
            PriceHistoryClient.DailyClose before = null;
            for (PriceHistoryClient.DailyClose c : r.closes()) {
                if (c.date().equals(t.exDate())) {
                    on = c.close();
                } else if (c.date().isBefore(t.exDate()) && (before == null || c.date().isAfter(before.date()))) {
                    before = c;
                }
            }
            BigDecimal pct = pctOf(on, before == null ? null : before.close());
            if (pct != null) {
                return pct;
            }
        }
        return null;
    }

    /** {@code exClose / previousClose x 100}, two decimals, clamped to 1..99; null when either price is missing. */
    static BigDecimal pctOf(@Nullable BigDecimal exClose, @Nullable BigDecimal previousClose) {
        if (exClose == null || previousClose == null || previousClose.signum() <= 0 || exClose.signum() <= 0) {
            return null;
        }
        BigDecimal pct = exClose.multiply(BigDecimal.valueOf(100)).divide(previousClose, 2, RoundingMode.HALF_UP);
        if (pct.doubleValue() < ESTIMATE_MIN) {
            return BigDecimal.valueOf(ESTIMATE_MIN).setScale(2);
        }
        if (pct.doubleValue() > ESTIMATE_MAX) {
            return BigDecimal.valueOf(ESTIMATE_MAX).setScale(2);
        }
        return pct;
    }
}
