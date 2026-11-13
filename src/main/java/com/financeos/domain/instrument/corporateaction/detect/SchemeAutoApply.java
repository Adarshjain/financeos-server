package com.financeos.domain.instrument.corporateaction.detect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.instrument.dto.CorporateActionSchemeHoldingsCheck;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.instrument.corporateaction.detect.SchemeHoldingsAgreement.Check;
import com.financeos.domain.instrument.corporateaction.detect.SchemeHoldingsAgreement.SnapRow;
import com.financeos.domain.instrument.corporateaction.detect.SchemeHoldingsAgreement.Trade;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.reconcile.BrokerHoldingSnapshot;
import com.financeos.domain.investment.reconcile.BrokerHoldingSnapshotRepository;
import com.financeos.domain.user.UserRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Applies a demerger or merger on its own when the company's notices and the user's own holdings tell the same story in
 * every broker account that held the parent. A single child with a ratio and a cost split published by the company is
 * required; a price estimate, several children, an account without evidence or one that disagrees, or anything the user
 * already acted on is left for the user. Runs for one user at a time on the worker (never in a request); one INFO line per run.
 */
@Service
public class SchemeAutoApply {

    private static final Logger log = LoggerFactory.getLogger(SchemeAutoApply.class);
    /** A demerger the user entered by hand this close to the notice's ex-date is theirs. */
    static final int HAND_ENTERED_DAYS = 7;
    /** A merger's record date must be this close (days) to the stopped stock's last price date. */
    static final int RECORD_DATE_WINDOW_DAYS = 45;
    /** Parent and child cost shares must add to 100 within this (the reader's display tolerance is wider). */
    static final BigDecimal COST_SUM_TOLERANCE = new BigDecimal("0.01");
    static final String CHECKED_NOTE = "Detected automatically from the company's notices and your holdings";

    /** What one run did. */
    public record Summary(int considered, int applied) {
    }

    private record ParentTrade(UUID accountId, LocalDate date, BigDecimal signedQty) {
    }

    private final CorporateActionSuggestionRepository suggestions;
    private final InvestmentTransactionRepository transactions;
    private final CorporateActionRepository actions;
    private final AccountRepository accounts;
    private final BrokerHoldingSnapshotRepository snapshots;
    private final SchemeEvidenceRepository evidence;
    private final SchemeEvidenceChildRepository children;
    private final SchemeCheckRepository checks;
    private final StockNotTradingRule notTradingRule;
    private final InstrumentPriceRepository prices;
    private final InstrumentSearchService instrumentSearch;
    private final CorporateActionService actionService;
    private final UserRepository users;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public SchemeAutoApply(CorporateActionSuggestionRepository suggestions, InvestmentTransactionRepository transactions,
                           CorporateActionRepository actions, AccountRepository accounts, BrokerHoldingSnapshotRepository snapshots,
                           SchemeEvidenceRepository evidence, SchemeEvidenceChildRepository children, SchemeCheckRepository checks,
                           StockNotTradingRule notTradingRule, InstrumentPriceRepository prices, InstrumentSearchService instrumentSearch,
                           CorporateActionService actionService, UserRepository users, TransactionTemplate tx, ObjectMapper mapper) {
        this.suggestions = suggestions;
        this.transactions = transactions;
        this.actions = actions;
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.evidence = evidence;
        this.children = children;
        this.checks = checks;
        this.notTradingRule = notTradingRule;
        this.prices = prices;
        this.instrumentSearch = instrumentSearch;
        this.actionService = actionService;
        this.users = users;
        this.tx = tx;
        this.mapper = mapper;
    }

    /** Checks and, where everything agrees, applies this user's pending demergers and no-longer-trading stocks. Never throws. */
    public Summary runForUser(UUID userId) {
        UUID previous = UserContext.getCurrentUserId();
        UserContext.setCurrentUserId(userId);
        int considered = 0;
        int applied = 0;
        try {
            List<CorporateActionSuggestion> pending = suggestions.findPendingUnresolved(userId).stream()
                    .filter(x -> x.getKind() == SuggestionKind.demerger && x.getInstrument().getSymbol() != null).toList();
            List<Object[]> stopped = notTradingRule.unanswered(userId).stream().filter(r -> r[3] != null).toList();
            if (pending.isEmpty() && stopped.isEmpty()) {
                return new Summary(0, 0); // nothing to decide: no trades, actions or snapshots are loaded
            }
            Context ctx = load(userId);
            for (CorporateActionSuggestion s : pending) {
                considered++;
                try {
                    if (demerger(userId, s, ctx)) {
                        applied++;
                        ctx.refresh(userId);
                    }
                } catch (RuntimeException e) {
                    log.warn("Scheme check failed for user {} {} {}: {}", userId, s.getInstrument().getSymbol(), s.getExDate(), e.toString());
                }
            }
            for (Object[] row : stopped) {
                String symbol = (String) row[3];
                considered++;
                try {
                    if (merger(userId, (UUID) row[0], symbol, ctx)) {
                        applied++;
                        ctx.refresh(userId);
                    }
                } catch (RuntimeException e) {
                    log.warn("Scheme check failed for user {} {}: {}", userId, symbol, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Scheme auto-apply failed for user {}: {}", userId, e.toString());
        } finally {
            if (previous == null) {
                UserContext.clear();
            } else {
                UserContext.setCurrentUserId(previous);
            }
        }
        if (considered > 0) {
            log.info("Scheme auto-apply for user {}: considered={} applied={}", userId, considered, applied);
        }
        return new Summary(considered, applied);
    }

    // ---- what is loaded once per user

    private final class Context {
        final List<InvestmentTransaction> trades;
        final Map<UUID, List<BrokerHoldingSnapshot>> snapshotByAccount;
        List<CorporateAction> actions;
        List<CorporateActionSuggestion> suggestions;

        Context(List<InvestmentTransaction> trades, Map<UUID, List<BrokerHoldingSnapshot>> snapshotByAccount, List<CorporateAction> actions,
                List<CorporateActionSuggestion> suggestions) {
            this.trades = trades;
            this.snapshotByAccount = snapshotByAccount;
            this.actions = actions;
            this.suggestions = suggestions;
        }

        /** Re-reads what an apply changes, so the next scheme of the run sees it. */
        void refresh(UUID userId) {
            this.actions = SchemeAutoApply.this.actions.findAllOwnedWithInstruments(userId);
            this.suggestions = SchemeAutoApply.this.suggestions.findAllOwned(userId);
        }
    }

    private Context load(UUID userId) {
        return new Context(transactions.findStockTradesOwned(userId),
                snapshots.findOwned(userId).stream().collect(Collectors.groupingBy(BrokerHoldingSnapshot::getBrokerAccountId)),
                actions.findAllOwnedWithInstruments(userId), suggestions.findAllOwned(userId));
    }

    // ---- demerger

    private boolean demerger(UUID userId, CorporateActionSuggestion s, Context ctx) {
        Instrument parent = s.getInstrument();
        String symbol = parent.getSymbol().trim().toUpperCase(Locale.ROOT);
        SchemeEvidence ev = evidence.findScheme(symbol, "demerger", s.getExDate()).orElse(null);
        if (ev == null) {
            return false;
        }
        List<SchemeEvidenceChild> kids = children.findForEvidence(List.of(ev.getId()));
        if (kids.size() != 1) {
            return false; // several children, or none: never on its own
        }
        SchemeEvidenceChild child = kids.get(0);
        if (child.getChildIsin() == null || child.getRatioFrom() == null || child.getRatioTo() == null
                || child.getRatioFrom() <= 0 || child.getRatioTo() <= 0) {
            return false;
        }
        List<Check> perAccount = perAccountChecks(ctx, parent, s.getExDate(), child, CorporateActionType.demerger, null);
        saveCheck(userId, parent, "demerger", s.getExDate(), perAccount);
        boolean noticeCost = SchemeEvidence.COST_NOTICE.equals(ev.getCostKind()) && ev.getParentCostPct() != null && child.getChildCostPct() != null;
        if (!noticeCost || !costSumsTo100(ev.getParentCostPct(), child.getChildCostPct()) || handEntered(ctx, parent, CorporateActionType.demerger, s.getExDate())
                || !SchemeHoldingsAgreement.allAgree(perAccount)) {
            return false;
        }
        return Boolean.TRUE.equals(tx.execute(status -> applyDemerger(userId, s, ev, child, perAccount)));
    }

    /** Parent + child cost shares equal 100 to within a hundredth; otherwise the scheme is held for the user with the values prefilled. */
    static boolean costSumsTo100(@Nullable BigDecimal parent, @Nullable BigDecimal child) {
        return parent != null && child != null && parent.add(child).subtract(BigDecimal.valueOf(100)).abs().compareTo(COST_SUM_TOLERANCE) <= 0;
    }

    /**
     * Applies in one transaction that first takes the user's row lock (so the nightly run and an import job never apply together) and
     * the suggestion's row lock, then re-checks, against fresh data, that the suggestion is still pending and that no same demerger
     * of that stock and date was recorded meanwhile. Returns false without changing anything when either check fails.
     */
    private boolean applyDemerger(UUID userId, CorporateActionSuggestion s, SchemeEvidence ev, SchemeEvidenceChild child, List<Check> perAccount) {
        users.lockById(userId);
        CorporateActionSuggestion locked = suggestions.lockOwnedById(s.getId(), userId).orElse(null);
        if (locked == null || locked.getStatus() != SuggestionStatus.pending) {
            return false; // dismissed or applied while this run was reading
        }
        UUID parentId = locked.getInstrument().getId();
        if (actions.existsSameDay(userId, parentId, CorporateActionType.demerger, locked.getExDate())) {
            return false;
        }
        Instrument target = resolveTarget(child);
        CorporateAction created = actionService.createDetectedWithTarget(userId, parentId, CorporateActionType.demerger,
                child.getRatioFrom(), child.getRatioTo(), locked.getExDate(), CHECKED_NOTE, target.getId(), child.getChildCostPct());
        if (created == null) {
            return false;
        }
        locked.setStatus(SuggestionStatus.auto_applied);
        locked.setReason(null);
        locked.setCorporateAction(created);
        locked.setAppliedBecause(because(child, perAccount));
        suggestions.save(locked);
        return true;
    }

    // ---- merger

    private boolean merger(UUID userId, UUID parentId, String rawSymbol, Context ctx) {
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        SchemeEvidence ev = evidence.findScheme(symbol, "merger", null).orElse(null);
        if (ev == null || ev.getRecordDate() == null) {
            return false;
        }
        List<SchemeEvidenceChild> kids = children.findForEvidence(List.of(ev.getId()));
        if (kids.size() != 1) {
            return false;
        }
        SchemeEvidenceChild acquirer = kids.get(0);
        if (acquirer.getChildIsin() == null || acquirer.getRatioFrom() == null || acquirer.getRatioTo() == null
                || acquirer.getRatioFrom() <= 0 || acquirer.getRatioTo() <= 0) {
            return false;
        }
        Instrument parent = instrumentOf(ctx, parentId);
        if (parent == null) {
            return false;
        }
        List<CorporateActionSuggestion> mine = ctx.suggestions.stream()
                .filter(x -> x.getKind() == SuggestionKind.merger && x.getInstrument().getId().equals(parentId)).toList();
        if (mine.stream().anyMatch(x -> x.getStatus() != SuggestionStatus.pending)) {
            return false; // the user decided, or it was applied and undone: left alone
        }
        if (!recordDateUsable(userId, parentId, ev)) {
            return false;
        }
        List<Check> perAccount = perAccountChecks(ctx, parent, ev.getRecordDate(), acquirer, CorporateActionType.merger, ev.getRecordDate());
        saveCheck(userId, parent, "merger", null, perAccount);
        if (handEntered(ctx, parent, CorporateActionType.merger, ev.getRecordDate()) || !SchemeHoldingsAgreement.allAgree(perAccount)) {
            return false;
        }
        CorporateActionSuggestion existing = mine.isEmpty() ? null : mine.get(0);
        return Boolean.TRUE.equals(tx.execute(status -> applyMerger(userId, parent, existing, ev, acquirer, perAccount)));
    }

    /**
     * A record date is used only when it is not in the future and lies within {@value #RECORD_DATE_WINDOW_DAYS} days of the stopped
     * stock's last price date; otherwise the merger is left for the user and the notice row says why.
     */
    private boolean recordDateUsable(UUID userId, UUID parentId, SchemeEvidence ev) {
        LocalDate record = ev.getRecordDate();
        LocalDate lastPrice = prices.findLatestByInstrumentIds(List.of(parentId), userId).stream().map(InstrumentPrice::getAsOf)
                .max(Comparator.naturalOrder()).orElse(null);
        if (record != null && !record.isAfter(AppTime.today()) && lastPrice != null
                && Math.abs(ChronoUnit.DAYS.between(lastPrice, record)) <= RECORD_DATE_WINDOW_DAYS) {
            return true;
        }
        flagRecordDateOutOfRange(ev);
        return false;
    }

    private void flagRecordDateOutOfRange(SchemeEvidence ev) {
        tx.executeWithoutResult(status -> evidence.findById(ev.getId()).ifPresent(e -> {
            String code = SchemeNoticeReader.RECORD_DATE_OUT_OF_RANGE;
            String missing = e.getMissing() == null ? "" : e.getMissing();
            if (!List.of(missing.split(",")).contains(code) && missing.length() + code.length() + 1 <= 300) {
                e.setMissing(missing.isEmpty() ? code : missing + "," + code);
                evidence.save(e);
            }
        }));
    }

    /**
     * As {@link #applyDemerger}: user row lock first, then fresh checks inside the transaction. A merger without a suggestion yet
     * is guarded the same way: any answer already recorded for the stock (merger or delisting), any decided merger suggestion
     * or a merger of that date means nothing is applied.
     */
    private boolean applyMerger(UUID userId, Instrument parent, @Nullable CorporateActionSuggestion existing, SchemeEvidence ev,
                                SchemeEvidenceChild acquirer, List<Check> perAccount) {
        users.lockById(userId);
        for (CorporateActionType answer : StockNotTradingRule.ANSWERS) {
            if (actions.existsOfType(userId, parent.getId(), answer)) {
                return false;
            }
        }
        if (actions.existsSameDay(userId, parent.getId(), CorporateActionType.merger, ev.getRecordDate())) {
            return false;
        }
        List<CorporateActionSuggestion> fresh = suggestions.findOwnedOfKindForInstrument(userId, parent.getId(), SuggestionKind.merger);
        if (fresh.stream().anyMatch(x -> x.getStatus() != SuggestionStatus.pending)) {
            return false;
        }
        CorporateActionSuggestion s = null;
        if (!fresh.isEmpty()) {
            s = suggestions.lockOwnedById(fresh.get(0).getId(), userId).orElse(null);
            if (s == null || s.getStatus() != SuggestionStatus.pending) {
                return false;
            }
        }
        Instrument target = resolveTarget(acquirer);
        CorporateAction created = actionService.createDetectedWithTarget(userId, parent.getId(), CorporateActionType.merger,
                acquirer.getRatioFrom(), acquirer.getRatioTo(), ev.getRecordDate(), CHECKED_NOTE, target.getId(), null);
        if (created == null) {
            return false;
        }
        if (s == null) {
            s = new CorporateActionSuggestion();
            s.setUser(users.getReferenceById(userId));
            s.setInstrument(parent);
            s.setKind(SuggestionKind.merger);
            s.setExDate(ev.getRecordDate());
            s.setCreatedAt(Instant.now());
        }
        s.setRatioFrom(acquirer.getRatioFrom());
        s.setRatioTo(acquirer.getRatioTo());
        s.setSubject("Merger into " + acquirer.getChildName());
        s.setStatus(SuggestionStatus.auto_applied);
        s.setReason(null);
        s.setCorporateAction(created);
        s.setAppliedBecause(because(acquirer, perAccount));
        suggestions.save(s);
        return true;
    }

    // ---- shared

    /** The check of every broker account that held the parent the day before {@code date}. */
    private List<Check> perAccountChecks(Context ctx, Instrument parent, LocalDate date, SchemeEvidenceChild child, CorporateActionType type,
                                         @Nullable LocalDate recordDate) {
        Map<UUID, List<ParentTrade>> parentTrades = new HashMap<>();
        Map<UUID, List<InvestmentTransaction>> childTrades = new HashMap<>();
        for (InvestmentTransaction t : ctx.trades) {
            Instrument i = t.getHolding().getInstrument();
            UUID account = t.getHolding().getBrokerAccount().getId();
            if (i.getId().equals(parent.getId())) {
                BigDecimal signed = t.getType() == InvestmentTransactionType.sell ? t.getQuantity().negate() : t.getQuantity();
                parentTrades.computeIfAbsent(account, k -> new ArrayList<>()).add(new ParentTrade(account, t.getTradeDate(), signed));
            } else if (i.getIsin() != null && i.getIsin().equalsIgnoreCase(child.getChildIsin())) {
                childTrades.computeIfAbsent(account, k -> new ArrayList<>()).add(t);
            }
        }
        List<CorporateAction> factors = ctx.actions.stream()
                .filter(a -> a.getInstrument().getId().equals(parent.getId())
                        && (a.getType() == CorporateActionType.split || a.getType() == CorporateActionType.bonus)
                        && a.getRatioFrom() != null && a.getRatioFrom() > 0 && a.getRatioTo() != null && a.getRatioTo() > 0)
                .toList();
        Map<UUID, String> names = accounts.findAllById(parentTrades.keySet()).stream().collect(Collectors.toMap(Account::getId, Account::getName));
        List<Check> out = new ArrayList<>();
        for (Map.Entry<UUID, List<ParentTrade>> e : new TreeMap<>(parentTrades).entrySet()) {
            BigDecimal held = heldBefore(e.getValue(), factors, date);
            if (held.signum() <= 0) {
                continue; // did not hold the parent that day
            }
            UUID account = e.getKey();
            List<SnapRow> snap = ctx.snapshotByAccount.getOrDefault(account, List.of()).stream()
                    .map(r -> new SnapRow(r.getIsin(), r.getQuantity(), r.getAsOfDate())).toList();
            List<Trade> trades = childTrades.getOrDefault(account, List.of()).stream()
                    .sorted(Comparator.comparing(InvestmentTransaction::getTradeDate).thenComparing(InvestmentTransaction::getCreatedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(t -> new Trade(t.getType() == InvestmentTransactionType.buy, t.getTradeDate(), t.getQuantity())).toList();
            String name = names.getOrDefault(account, "");
            out.add(type == CorporateActionType.demerger
                    ? SchemeHoldingsAgreement.demerger(account, name, held, child.getRatioFrom(), child.getRatioTo(), child.getChildIsin(),
                            child.getListedOn(), date, snap, trades)
                    : SchemeHoldingsAgreement.merger(account, name, held, child.getRatioFrom(), child.getRatioTo(), child.getChildIsin(),
                            recordDate, snap, trades));
        }
        return out;
    }

    /** The parent quantity held before {@code date}: the buys less the sells before it, each carried through the splits and bonuses after it. */
    static BigDecimal heldBefore(List<ParentTrade> trades, List<CorporateAction> factors, LocalDate date) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ParentTrade t : trades) {
            if (!t.date().isBefore(date)) {
                continue;
            }
            BigDecimal q = t.signedQty();
            for (CorporateAction a : factors) {
                if (a.getExDate().isAfter(t.date()) && a.getExDate().isBefore(date)) {
                    q = q.multiply(BigDecimal.valueOf(a.getRatioTo())).divide(BigDecimal.valueOf(a.getRatioFrom()), 10, RoundingMode.HALF_UP);
                }
            }
            sum = sum.add(q);
        }
        return sum;
    }

    /** True when the user already recorded a demerger or merger of the parent near {@code date}, by hand or by an earlier run. */
    private static boolean handEntered(Context ctx, Instrument parent, CorporateActionType type, LocalDate date) {
        for (CorporateAction a : ctx.actions) {
            if (a.getInstrument().getId().equals(parent.getId())
                    && (a.getType() == type || (type == CorporateActionType.merger && a.getType() == CorporateActionType.delisted))
                    && Math.abs(ChronoUnit.DAYS.between(a.getExDate(), date)) <= (type == CorporateActionType.merger ? 366 : HAND_ENTERED_DAYS)) {
                return true;
            }
        }
        return false;
    }

    private static Instrument instrumentOf(Context ctx, UUID id) {
        for (InvestmentTransaction t : ctx.trades) {
            if (t.getHolding().getInstrument().getId().equals(id)) {
                return t.getHolding().getInstrument();
            }
        }
        return null;
    }

    /** The catalog instrument for the child or acquirer: the existing row with its ISIN, else a new row (an existing row is never changed). */
    private Instrument resolveTarget(SchemeEvidenceChild child) {
        return instrumentSearch.resolveInstrument(new ResolveInstrumentRequest(InstrumentType.stock, child.getChildName(), child.getChildSymbol(),
                "NSE", child.getChildIsin(), null, child.getChildSymbol() + ".NS", "INR", null), InstrumentSearchService.CatalogWrite.CREATE_ONLY);
    }

    private void saveCheck(UUID userId, Instrument parent, String kind, @Nullable LocalDate exDate, List<Check> perAccount) {
        List<CorporateActionSchemeHoldingsCheck> dto = perAccount.stream().filter(c -> !c.neutral()).map(c -> new CorporateActionSchemeHoldingsCheck(c.brokerAccountId(),
                c.accountName(), c.source(), c.asOf(), c.expectedQty(), c.foundQty(), c.agrees())).toList();
        String json;
        try {
            json = mapper.writeValueAsString(dto);
        } catch (Exception e) {
            return;
        }
        String stored = json.length() <= 4000 ? json : "[]";
        tx.executeWithoutResult(status -> {
            checks.deleteFor(userId, parent.getId(), kind);
            SchemeCheck row = new SchemeCheck();
            row.setUser(users.getReferenceById(userId));
            row.setInstrument(parent);
            row.setKind(kind);
            row.setExDate(exDate);
            row.setAgrees(SchemeHoldingsAgreement.allAgree(perAccount));
            row.setChecksJson(stored);
            row.setCheckedAt(Instant.now());
            checks.save(row);
        });
    }

    /** "Notice <url> (<date>); SNAPSHOT <account> as of <date> qty <n>; ..." within the column's 600 characters. */
    static String because(SchemeEvidenceChild c, List<Check> perAccount) {
        StringBuilder sb = new StringBuilder("Notice ").append(c.getRatioUrl() == null ? "" : c.getRatioUrl());
        if (c.getRatioNoticeDate() != null) {
            sb.append(" (").append(c.getRatioNoticeDate()).append(')');
        }
        Set<String> parts = new HashSet<>();
        for (Check k : perAccount) {
            String part = k.source() + " " + k.accountName() + (k.asOf() == null ? "" : " as of " + k.asOf()) + " qty " + k.foundQty().stripTrailingZeros().toPlainString();
            if (parts.add(part)) {
                sb.append("; ").append(part);
            }
        }
        return sb.length() <= 600 ? sb.toString() : sb.substring(0, 600);
    }
}
