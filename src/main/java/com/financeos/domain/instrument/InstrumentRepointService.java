package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moves one user from one catalog instrument to another when they change the identifiers (ISIN, AMFI
 * code, Yahoo symbol) that pick its price feed. The shared rows stay as they are for every other user;
 * only this user's references follow: holdings (merged into their existing holding of the target at
 * the same broker, trades, dividends and intraday classifications moving with them), intraday
 * classifications without a holding, SIPs, their own MANUAL prices, their own import aliases and their
 * asset-class override, and their own corporate actions (as the instrument they apply to and as the
 * demerger child / merger acquirer), so their positions and cost are the same on the target as on the
 * source. The move is recorded ({@link InstrumentRepointMap}) so the user's later imports of the source
 * land on the target. A price fetch for the target follows the commit.
 *
 * <p>Corporate actions are per user, so carrying them touches no one else's. A repoint never changes
 * the user's positions or cost, so onto an EXISTING target two moves are refused (400, naming the
 * actions): one that would leave a demerger / merger of the user's pointing from an instrument into
 * itself (the source and target are its two sides), and one that would let one of the user's actions
 * reach lots it did not reach before — an action on the target while the source has lots (the moved
 * lots would get it), or an action on the source while the target has lots (the target's lots, at any
 * broker, would get it). A new target has no actions and no lots, so nothing is refused there. As a
 * safety net on top of that rule, the user's figures (every holding's open quantity and cost, and
 * realised gains) are computed before and after the move in the same transaction; a difference rolls
 * the move back with a 400. Holdings merged at one broker are the exception: their trades become one
 * FIFO history, so only their quantity must match ({@link Result#mergeChangedFigures} says whether
 * their cost or realised gains moved).
 */
@Service
@Transactional
public class InstrumentRepointService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentRepointService.class);

    static final String ALIAS_SOURCE = "USER_EDIT";

    private final InstrumentSearchService searchService;
    private final InstrumentRepository instrumentRepository;
    private final AssetClassOverrideService overrideService;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final CorporateActionRepository corporateActionRepository;
    private final InstrumentRepointMap repointMap;
    private final InvestmentService investmentService;

    public InstrumentRepointService(InstrumentSearchService searchService,
                                    InstrumentRepository instrumentRepository,
                                    AssetClassOverrideService overrideService,
                                    JdbcTemplate jdbc,
                                    EntityManager entityManager,
                                    org.springframework.context.ApplicationEventPublisher eventPublisher,
                                    CorporateActionRepository corporateActionRepository,
                                    InstrumentRepointMap repointMap,
                                    InvestmentService investmentService) {
        this.searchService = searchService;
        this.instrumentRepository = instrumentRepository;
        this.overrideService = overrideService;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.corporateActionRepository = corporateActionRepository;
        this.repointMap = repointMap;
        this.investmentService = investmentService;
    }

    /**
     * The outcome of a repoint: the instrument the user now holds, whether it was added to the catalog
     * for this edit, whether a holding was merged into one they already had of it, and whether merging
     * changed the merged holdings' open cost or realised gains (their trades became one FIFO history).
     */
    public record Result(Instrument target, boolean created, boolean merged, boolean mergeChangedFigures) {
    }

    /**
     * The catalog instrument the request's changed identifiers name (ISIN first, then AMFI code, then
     * Yahoo symbol — only the ones that differ from {@code source}'s: an unchanged one still names the
     * source), with {@code userId}'s references to {@code source} moved onto it. When none has them, a
     * new catalog row with the changed identifiers is added; its display fields are the source's
     * catalog values except those the user changed ({@code changes}). When that new row cannot take the
     * ticker (another row has it), the user's previous ticker (symbol + exchange as they saw them) is
     * pinned as their own override so their holding keeps showing it. Fails (400) when the identifiers
     * lead back to {@code source} itself, when every changed identifier was cleared, when one of the
     * user's demergers / mergers has {@code source} and the target as its two sides, when one of the
     * user's corporate actions would reach lots it did not reach before, or when the user's figures
     * would change anyway (the move is then rolled back).
     */
    public Result repoint(UUID userId, Instrument source, InstrumentRequest request, DisplayChanges changes) {
        String isin = changed(source.getIsin(), request.isin());
        String amfi = changed(source.getAmfiCode(), request.amfiCode());
        String yahoo = changed(source.getYahooSymbol(), request.yahooSymbol());
        if (isin == null && amfi == null && yahoo == null) {
            throw new ValidationException(String.format(
                    "Clearing an identifier can't change the price feed of %s: it is a shared catalog instrument (%s). "
                            + "Enter another ISIN, AMFI code or Yahoo symbol, or a manual price.",
                    source.getName(), describeFeed(source)));
        }
        Instrument target = searchService.findByFeedIdentifiers(isin, amfi, yahoo).orElse(null);
        if (target != null && target.getId().equals(source.getId())) {
            throw new ValidationException(String.format(
                    "%s is a shared catalog instrument (%s); its price feed can't be changed for one account. "
                            + "Enter a manual price instead, or use an ISIN, AMFI code or Yahoo symbol of another instrument.",
                    source.getName(), describeFeed(source)));
        }
        refuseSelfReferencingActions(userId, source, target);
        refuseActionsReachingOtherLots(userId, source, target);

        boolean created = false;
        boolean tickerDropped = false;
        String keptSymbol = null;
        String keptExchange = null;
        if (target == null) {
            String symbol = changes.symbol() ? blankToNull(request.symbol()) : source.getSymbol();
            String exchange = changes.exchange() ? blankToNull(request.exchange()) : source.getExchange();
            // A ticker is unique in the catalog (symbol + exchange): when another row already has it
            // (typically the source itself) the new row goes without the symbol; the ticker the user
            // typed, or else the one they saw on the source, then shows as their own override.
            boolean tickerTaken = symbol != null && exchange != null
                    && instrumentRepository.existsTickerIgnoreCase(symbol.trim(), exchange.trim());
            if (tickerTaken) {
                // The ticker as the user saw it (their override over the source's catalog value).
                InstrumentOverrides seen = InstrumentOverrides.orNone(overrideService.overridesFor(userId));
                tickerDropped = true;
                keptSymbol = changes.symbol() ? blankToNull(request.symbol()) : blankToNull(seen.symbol(source));
                keptExchange = changes.exchange() ? blankToNull(request.exchange()) : blankToNull(seen.exchange(source));
            }
            target = searchService.createFromRequest(new ResolveInstrumentRequest(
                    changes.type() && request.type() != null ? request.type() : source.getType(),
                    changes.name() && !blank(request.name()) ? request.name() : source.getName(),
                    tickerTaken ? null : symbol,
                    exchange,
                    isin, amfi, yahoo,
                    changes.currency() && !blank(request.currency()) ? request.currency() : source.getCurrency(),
                    null));
            created = true;
        }
        Instrument finalTarget = target;

        // Pending entity changes first, so the SQL below sees and is not overwritten by them.
        entityManager.flush();
        List<InvestmentService.HoldingFigures> figuresBefore = investmentService.figuresOf(userId);
        Moved moved = moveReferences(userId, source, finalTarget);
        entityManager.clear();
        boolean mergeChangedFigures = verifyFiguresUnchanged(userId, source, finalTarget, figuresBefore,
                investmentService.figuresOf(userId), moved.mergedBrokers());
        entityManager.clear();

        overrideService.moveAssetClass(userId, source.getId(), finalTarget.getId());
        Instrument reloaded = entityManager.find(Instrument.class, finalTarget.getId());
        if (tickerDropped && reloaded != null) {
            // The new row went without the ticker; the user keeps seeing theirs as an override.
            overrideService.setDisplay(userId, reloaded, null, keptSymbol, keptExchange, null, null,
                    new DisplayChanges(false, true, true, false, false));
        }
        repointMap.record(userId, source.getId(), finalTarget.getId());
        log.info("Repointed user {} from instrument {} to {}: {}", userId, source.getId(), finalTarget.getId(), moved);
        eventPublisher.publishEvent(new PriceRefreshEvent(Set.of(finalTarget.getId())));
        return new Result(reloaded != null ? reloaded : finalTarget, created, moved.holdingsMerged() > 0,
                mergeChangedFigures);
    }
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @org.springframework.lang.Nullable
    private static String blankToNull(@org.springframework.lang.Nullable String value) {
        return blank(value) ? null : value.trim();
    }

    /** {@code requested} (trimmed) when it is set and differs from {@code current} (ignoring case); else null. */
    @org.springframework.lang.Nullable
    static String changed(@org.springframework.lang.Nullable String current,
                          @org.springframework.lang.Nullable String requested) {
        String r = blankToNull(requested);
        if (r == null) {
            return null;
        }
        String c = blankToNull(current);
        return c != null && c.equalsIgnoreCase(r) ? null : r;
    }

    private static String describeFeed(Instrument i) {
        StringBuilder sb = new StringBuilder();
        append(sb, "ISIN", i.getIsin());
        append(sb, "AMFI", i.getAmfiCode());
        append(sb, "Yahoo", i.getYahooSymbol());
        return sb.length() == 0 ? "no price feed" : sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(label).append(' ').append(value);
        }
    }

    /**
     * Refuses the move when one of {@code userId}'s demergers / mergers has {@code source} on one side
     * and {@code target} on the other: carried over, it would turn an instrument into itself. Only an
     * existing target can be one (a new catalog row has no corporate actions yet). Other users'
     * corporate actions are theirs and never block this user's move.
     */
    private void refuseSelfReferencingActions(UUID userId, Instrument source,
                                              @org.springframework.lang.Nullable Instrument target) {
        if (target == null) {
            return;
        }
        List<String> described = new java.util.ArrayList<>();
        for (CorporateAction ca : corporateActionRepository.findOwnedInvolving(userId, List.of(source.getId(), target.getId()))) {
            if (ca.getInstrument() == null || ca.getTargetInstrument() == null) {
                continue;
            }
            UUID from = movedId(ca.getInstrument().getId(), source, target);
            UUID to = movedId(ca.getTargetInstrument().getId(), source, target);
            if (from.equals(to)) {
                described.add(describe(ca));
            }
        }
        if (described.isEmpty()) {
            return;
        }
        throw new ValidationException(String.format(
                "Can't switch %s to %s: your %s would then be from %s into itself. Edit or delete that corporate "
                        + "action first.",
                source.getName(), target.getName(), String.join("; ", described), target.getName()));
    }

    /**
     * Refuses a move onto an existing {@code target} that would let one of {@code userId}'s corporate
     * actions reach lots it does not reach today (it would change their quantities and cost): an action
     * on the target (split, bonus, demerger parent, merger transferor) while the source has lots — the
     * moved lots would get it — or one on the source while the target has lots, at any broker. A side
     * "has lots" when the user traded it or one of their demergers / mergers seeds shares into it.
     * Actions that only seed shares into one side carry over without reaching anything new.
     */
    private void refuseActionsReachingOtherLots(UUID userId, Instrument source,
                                                @org.springframework.lang.Nullable Instrument target) {
        if (target == null) {
            return;
        }
        List<CorporateAction> involved = corporateActionRepository.findOwnedInvolving(userId,
                List.of(source.getId(), target.getId()));
        boolean sourceHasLots = hasLots(userId, source.getId(), involved);
        boolean targetHasLots = hasLots(userId, target.getId(), involved);
        List<String> reasons = new java.util.ArrayList<>();
        for (CorporateAction ca : involved) {
            UUID on = ca.getInstrument() != null ? ca.getInstrument().getId() : null;
            if (target.getId().equals(on) && sourceHasLots) {
                reasons.add("your " + describe(ca) + " would also apply to the shares moved from " + source.getName());
            } else if (source.getId().equals(on) && targetHasLots) {
                reasons.add("your " + describe(ca) + " would also apply to the " + target.getName()
                        + " shares you already hold");
            }
        }
        if (reasons.isEmpty()) {
            return;
        }
        throw new ValidationException(String.format(
                "Can't switch %s to %s: %s. That would change your quantities and cost, and a switch keeps them as "
                        + "they are. Edit or delete that corporate action first.",
                source.getName(), target.getName(), String.join("; ", reasons)));
    }

    /** Whether {@code userId} has lots of {@code instrumentId}: a trade of it, or one of their demergers / mergers seeding into it. */
    private boolean hasLots(UUID userId, UUID instrumentId, List<CorporateAction> involved) {
        for (CorporateAction ca : involved) {
            boolean seeds = ca.getType() == com.financeos.domain.instrument.corporateaction.CorporateActionType.demerger
                    || ca.getType() == com.financeos.domain.instrument.corporateaction.CorporateActionType.merger;
            if (seeds && ca.getTargetInstrument() != null && ca.getTargetInstrument().getId().equals(instrumentId)) {
                return true;
            }
        }
        Integer trades = jdbc.queryForObject("SELECT COUNT(*) FROM investment_transactions t JOIN holdings h "
                + "ON h.id = t.holding_id WHERE h.user_id = ? AND h.instrument_id = ?", Integer.class,
                userId.toString(), instrumentId.toString());
        return trades != null && trades > 0;
    }

    /**
     * The safety net under the rules above: {@code userId}'s figures after the move must equal those
     * before, with the source's holdings counted as the target's. Per (broker, instrument): open
     * quantity always; open cost and realised gains too, except where a source holding was merged into
     * the target's at that broker (one FIFO history now). Any other difference — or a holding the
     * engine can no longer compute — throws (400), rolling the whole move back.
     *
     * @return whether a merged holding's open cost or realised gains moved (beyond rounding)
     */
    static boolean verifyFiguresUnchanged(UUID userId, Instrument source, Instrument target,
                                           List<InvestmentService.HoldingFigures> before,
                                           List<InvestmentService.HoldingFigures> after,
                                           Set<String> mergedBrokers) {
        Map<String, Figures> was = aggregate(before, source.getId(), target.getId());
        Map<String, Figures> now = aggregate(after, source.getId(), target.getId());
        Set<String> keys = new java.util.TreeSet<>(was.keySet());
        keys.addAll(now.keySet());
        List<String> changed = new java.util.ArrayList<>();
        boolean mergeChanged = false;
        for (String key : keys) {
            Figures a = was.getOrDefault(key, Figures.NONE);
            Figures b = now.getOrDefault(key, Figures.NONE);
            boolean merged = mergedBrokers.contains(key.substring(0, key.indexOf('|')))
                    && key.endsWith("|" + target.getId());
            boolean same = (b.error() == null || a.error() != null) && a.quantity().compareTo(b.quantity()) == 0;
            if (same && !merged) {
                same = a.openCost().compareTo(b.openCost()) == 0 && a.realized().compareTo(b.realized()) == 0;
            }
            if (merged && same && (a.openCost().subtract(b.openCost()).abs().compareTo(CENT) > 0
                    || a.realized().subtract(b.realized()).abs().compareTo(CENT) > 0)) {
                mergeChanged = true;
            }
            if (!same) {
                Figures named = b.label() != null ? b : a;
                changed.add(named.label() + " (quantity " + a.quantity().stripTrailingZeros().toPlainString() + " → "
                        + b.quantity().stripTrailingZeros().toPlainString() + ", cost "
                        + a.openCost().stripTrailingZeros().toPlainString() + " → "
                        + b.openCost().stripTrailingZeros().toPlainString() + ")");
            }
        }
        if (!changed.isEmpty()) {
            log.warn("Repoint of user {} from {} to {} refused, figures would change: {}", userId, source.getId(),
                    target.getId(), changed);
            throw new ValidationException(String.format(
                    "Can't switch %s to %s: it would change your positions — %s. A switch keeps quantities and cost "
                            + "as they are; check your corporate actions on these instruments first.",
                    source.getName(), target.getName(), String.join("; ", changed)));
        }
        return mergeChanged;
    }

    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** One (broker, instrument)'s summed figures; {@code label} names it for a message. */
    private record Figures(BigDecimal quantity, BigDecimal openCost, BigDecimal realized,
                           @org.springframework.lang.Nullable String error,
                           @org.springframework.lang.Nullable String label) {
        static final Figures NONE = new Figures(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null);

        Figures plus(InvestmentService.HoldingFigures h, String label) {
            return new Figures(quantity.add(h.quantity()), openCost.add(h.openCost()),
                    realized.add(h.realized()).add(h.intradayRealized()), error != null ? error : h.error(), label);
        }
    }

    /** Figures per "broker|instrument", the source's holdings counted as the target's. */
    private static Map<String, Figures> aggregate(List<InvestmentService.HoldingFigures> figures, UUID source, UUID target) {
        Map<String, Figures> out = new java.util.HashMap<>();
        for (InvestmentService.HoldingFigures h : figures) {
            UUID instrument = h.instrumentId().equals(source) ? target : h.instrumentId();
            String key = h.brokerAccountId() + "|" + instrument;
            String label = h.instrumentName() + " at " + h.brokerName();
            out.put(key, out.getOrDefault(key, Figures.NONE).plus(h, label));
        }
        return out;
    }

    /** {@code id} after the user's move from {@code source} to {@code target}. */
    private static UUID movedId(UUID id, Instrument source, Instrument target) {
        return id.equals(source.getId()) ? target.getId() : id;
    }

    private static String describe(CorporateAction ca) {
        String instrument = ca.getInstrument() != null ? ca.getInstrument().getName() : "?";
        String target = ca.getTargetInstrument() != null ? ca.getTargetInstrument().getName() : null;
        String what = switch (ca.getType()) {
            case split -> "split of " + instrument + " " + ca.getRatioFrom() + ":" + ca.getRatioTo();
            // Stored held → held-after (a 1:1 bonus is 1 → 2); named the usual way, new : held.
            case bonus -> "bonus on " + instrument
                    + (ca.getRatioFrom() != null && ca.getRatioTo() != null
                            ? " " + (ca.getRatioTo() - ca.getRatioFrom()) + ":" + ca.getRatioFrom() : "");
            case demerger -> "demerger of " + instrument + (target != null ? " into " + target : "");
            case merger -> "merger of " + instrument + (target != null ? " into " + target : "");
        };
        return what + " on " + ca.getExDate();
    }

    /** What a repoint moved, for the log. */
    record Moved(int holdingsMoved, int holdingsMerged, Set<String> mergedBrokers, int sips, int prices,
                 int aliases, int corporateActions) {
    }

    private Moved moveReferences(UUID userId, Instrument source, Instrument target) {
        String user = userId.toString();
        String from = source.getId().toString();
        String to = target.getId().toString();
        int movedHoldings = 0;
        int mergedHoldings = 0;
        Set<String> mergedBrokers = new java.util.HashSet<>();

        List<Map<String, Object>> holdings = jdbc.queryForList(
                "SELECT id, broker_account_id, notes FROM holdings WHERE user_id = ? AND instrument_id = ?", user, from);
        for (Map<String, Object> h : holdings) {
            String holdingId = String.valueOf(h.get("id"));
            String brokerId = String.valueOf(h.get("broker_account_id"));
            List<String> existing = jdbc.queryForList(
                    "SELECT id FROM holdings WHERE user_id = ? AND broker_account_id = ? AND instrument_id = ?",
                    String.class, user, brokerId, to);
            if (existing.isEmpty()) {
                jdbc.update("UPDATE holdings SET instrument_id = ? WHERE id = ?", to, holdingId);
                moveClassifications("holding_id = ?", new Object[]{holdingId}, to, holdingId);
                movedHoldings++;
            } else {
                String keepId = existing.get(0);
                mergedBrokers.add(brokerId);
                jdbc.update("UPDATE investment_transactions SET holding_id = ? WHERE holding_id = ?", keepId, holdingId);
                jdbc.update("UPDATE dividends SET holding_id = ? WHERE holding_id = ?", keepId, holdingId);
                moveClassifications("holding_id = ?", new Object[]{holdingId}, to, keepId);
                Object notes = h.get("notes");
                if (notes != null && !String.valueOf(notes).isBlank()) {
                    jdbc.update("UPDATE holdings SET notes = CASE WHEN notes IS NULL OR notes = '' THEN ? "
                            + "ELSE notes END WHERE id = ?", String.valueOf(notes), keepId);
                }
                jdbc.update("DELETE FROM holdings WHERE id = ?", holdingId);
                mergedHoldings++;
            }
        }
        // Intraday classifications recorded before a holding existed.
        moveClassifications("holding_id IS NULL AND user_id = ? AND instrument_id = ?", new Object[]{user, from}, to, null);

        int sips = jdbc.update("UPDATE sips SET instrument_id = ? WHERE user_id = ? AND instrument_id = ?", to, user, from);

        // The user's own corporate actions follow (as the instrument and as the child / acquirer), so
        // their splits, bonuses, demergers and mergers apply to the moved lots exactly as before.
        int corporateActions = jdbc.update("UPDATE corporate_actions SET instrument_id = ? WHERE user_id = ? "
                + "AND instrument_id = ?", to, user, from);
        corporateActions += jdbc.update("UPDATE corporate_actions SET target_instrument_id = ? WHERE user_id = ? "
                + "AND target_instrument_id = ?", to, user, from);

        // The user's own manual prices; where they already priced the target on that date, theirs there stays.
        jdbc.update("DELETE FROM instrument_prices WHERE user_id = ? AND instrument_id = ? AND as_of IN ("
                + "SELECT t.as_of FROM instrument_prices t WHERE t.user_id = ? AND t.instrument_id = ?)", user, from, user, to);
        int prices = jdbc.update("UPDATE instrument_prices SET instrument_id = ? WHERE user_id = ? AND instrument_id = ?",
                to, user, from);

        int aliases = jdbc.update("UPDATE instrument_aliases SET instrument_id = ? WHERE user_id = ? AND instrument_id = ?",
                to, user, from);
        // The source's symbol now means the target for this user's imports (the catalog still has the
        // source under that symbol, possibly the target too, so a symbol match alone is ambiguous).
        String oldSymbol = source.getSymbol();
        if (oldSymbol != null && !oldSymbol.isBlank()) {
            Integer already = jdbc.queryForObject("SELECT COUNT(*) FROM instrument_aliases WHERE user_id = ? "
                    + "AND UPPER(old_symbol) = UPPER(?)", Integer.class, user, oldSymbol.trim());
            if (already != null && already > 0) {
                jdbc.update("UPDATE instrument_aliases SET instrument_id = ? WHERE user_id = ? AND UPPER(old_symbol) = UPPER(?)",
                        to, user, oldSymbol.trim());
            } else {
                jdbc.update("INSERT INTO instrument_aliases (id, instrument_id, old_symbol, old_name, source, user_id, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        UUID.randomUUID().toString(), to, oldSymbol.trim(), truncate(source.getName(), 255),
                        ALIAS_SOURCE, user);
                aliases++;
            }
        }
        return new Moved(movedHoldings, mergedHoldings, mergedBrokers, sips, prices, aliases, corporateActions);
    }

    /**
     * Moves the user's intraday classifications matching {@code where} to {@code toInstrument} (and
     * {@code toHolding} when given). A classification is per (broker, instrument, trade date), so one
     * that lands on a date the target already has is added into that row instead.
     */
    private void moveClassifications(String where, Object[] args, String toInstrument,
                                     @org.springframework.lang.Nullable String toHolding) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, broker_account_id, trade_date, intraday_qty, intraday_buy_value, intraday_sell_value "
                        + "FROM trade_settlement_classifications WHERE " + where, args);
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            Object tradeDate = row.get("trade_date");
            List<String> clash = jdbc.queryForList(
                    "SELECT id FROM trade_settlement_classifications WHERE broker_account_id = ? AND instrument_id = ? "
                            + "AND trade_date = ? AND id <> ?",
                    String.class, row.get("broker_account_id"), toInstrument, toSqlDate(tradeDate), id);
            if (clash.isEmpty()) {
                if (toHolding != null) {
                    jdbc.update("UPDATE trade_settlement_classifications SET instrument_id = ?, holding_id = ? WHERE id = ?",
                            toInstrument, toHolding, id);
                } else {
                    jdbc.update("UPDATE trade_settlement_classifications SET instrument_id = ? WHERE id = ?", toInstrument, id);
                }
            } else {
                jdbc.update("UPDATE trade_settlement_classifications SET intraday_qty = intraday_qty + ?, "
                                + "intraday_buy_value = intraday_buy_value + ?, intraday_sell_value = intraday_sell_value + ? "
                                + "WHERE id = ?",
                        decimal(row.get("intraday_qty")), decimal(row.get("intraday_buy_value")),
                        decimal(row.get("intraday_sell_value")), clash.get(0));
                jdbc.update("DELETE FROM trade_settlement_classifications WHERE id = ?", id);
            }
        }
    }

    private static Object toSqlDate(Object value) {
        if (value instanceof java.time.LocalDate ld) {
            return Date.valueOf(ld);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return Date.valueOf(ts.toLocalDateTime().toLocalDate());
        }
        return value;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
