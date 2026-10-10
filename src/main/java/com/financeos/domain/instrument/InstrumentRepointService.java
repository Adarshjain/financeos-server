package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.instrument.search.InstrumentSearchService;
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
 * asset-class override. The move is recorded ({@link InstrumentRepointMap}) so the user's later imports
 * of the source land on the target. A price fetch for the target follows the commit.
 *
 * <p>Refused when either instrument takes part in a corporate action: those are global and keyed by
 * instrument, so moving one user's lots across would detach a demerger / merger child from its parent
 * holding or apply a split / bonus to the wrong lots.
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

    public InstrumentRepointService(InstrumentSearchService searchService,
                                    InstrumentRepository instrumentRepository,
                                    AssetClassOverrideService overrideService,
                                    JdbcTemplate jdbc,
                                    EntityManager entityManager,
                                    org.springframework.context.ApplicationEventPublisher eventPublisher,
                                    CorporateActionRepository corporateActionRepository,
                                    InstrumentRepointMap repointMap) {
        this.searchService = searchService;
        this.instrumentRepository = instrumentRepository;
        this.overrideService = overrideService;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.corporateActionRepository = corporateActionRepository;
        this.repointMap = repointMap;
    }

    /**
     * The outcome of a repoint: the instrument the user now holds, whether it was added to the catalog
     * for this edit, whether a holding was merged into one they already had of it, and whether both
     * merged holdings had sells (realised gains may then change).
     */
    public record Result(Instrument target, boolean created, boolean merged, boolean mergedWithSells) {
    }

    /**
     * The catalog instrument the request's changed identifiers name (ISIN first, then AMFI code, then
     * Yahoo symbol — only the ones that differ from {@code source}'s: an unchanged one still names the
     * source), with {@code userId}'s references to {@code source} moved onto it. When none has them, a
     * new catalog row with the changed identifiers is added; its display fields are the source's
     * catalog values except those the user changed ({@code changes}). Fails (400) when the identifiers
     * lead back to {@code source} itself, when every changed identifier was cleared, or when either
     * instrument takes part in a corporate action.
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
        refuseAcrossCorporateActions(source, target);

        boolean created = false;
        if (target == null) {
            String symbol = changes.symbol() ? blankToNull(request.symbol()) : source.getSymbol();
            String exchange = changes.exchange() ? blankToNull(request.exchange()) : source.getExchange();
            // A ticker is unique in the catalog (symbol + exchange): when another row already has it
            // (typically the source itself) the new row goes without the symbol; a symbol the user
            // typed then shows as their own override.
            boolean tickerTaken = symbol != null && exchange != null
                    && instrumentRepository.existsTickerIgnoreCase(symbol.trim(), exchange.trim());
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
        Moved moved = moveReferences(userId, source, finalTarget);
        entityManager.clear();

        overrideService.moveAssetClass(userId, source.getId(), finalTarget.getId());
        repointMap.record(userId, source.getId(), finalTarget.getId());
        Instrument reloaded = entityManager.find(Instrument.class, finalTarget.getId());
        log.info("Repointed user {} from instrument {} to {}: {}", userId, source.getId(), finalTarget.getId(), moved);
        eventPublisher.publishEvent(new PriceRefreshEvent(Set.of(finalTarget.getId())));
        return new Result(reloaded != null ? reloaded : finalTarget, created, moved.holdingsMerged() > 0,
                moved.mergedWithSells());
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
     * Refuses the move when {@code source} or {@code target} takes part in any corporate action (as
     * the instrument it applies to or as its target), whatever its date. Corporate actions are global
     * and keyed by instrument: a demerger / merger child finds its parent holding by (broker, parent
     * instrument), and splits / bonuses adjust the lots of the instrument they name, so moving one
     * user's lots across would detach or mis-adjust them. Future-dated ones count too: they will apply.
     */
    private void refuseAcrossCorporateActions(Instrument source, @org.springframework.lang.Nullable Instrument target) {
        List<UUID> ids = target == null ? List.of(source.getId()) : List.of(source.getId(), target.getId());
        List<CorporateAction> actions = corporateActionRepository.findInvolving(ids);
        if (actions.isEmpty()) {
            return;
        }
        List<String> described = new java.util.ArrayList<>();
        for (CorporateAction ca : actions) {
            described.add(describe(ca));
        }
        String who = target == null ? source.getName() + " is" : source.getName() + " or " + target.getName() + " is";
        throw new ValidationException(String.format(
                "Can't switch the price feed of %s for one account: %s part of %s (%s). Corporate actions are "
                        + "shared by every holder of an instrument, so one account's lots can't be moved across them. "
                        + "Enter a manual price instead.",
                source.getName(), who,
                actions.size() == 1 ? "a corporate action" : actions.size() + " corporate actions",
                String.join("; ", described)));
    }

    private static String describe(CorporateAction ca) {
        String instrument = ca.getInstrument() != null ? ca.getInstrument().getName() : "?";
        String target = ca.getTargetInstrument() != null ? ca.getTargetInstrument().getName() : null;
        String what = switch (ca.getType()) {
            case split -> "split of " + instrument + " " + ca.getRatioFrom() + ":" + ca.getRatioTo();
            case bonus -> "bonus on " + instrument + " " + ca.getRatioTo() + ":" + ca.getRatioFrom();
            case demerger -> "demerger of " + instrument + (target != null ? " into " + target : "");
            case merger -> "merger of " + instrument + (target != null ? " into " + target : "");
        };
        return what + " on " + ca.getExDate();
    }

    /** What a repoint moved, for the log. */
    record Moved(int holdingsMoved, int holdingsMerged, boolean mergedWithSells, int sips, int prices, int aliases) {
    }

    private Moved moveReferences(UUID userId, Instrument source, Instrument target) {
        String user = userId.toString();
        String from = source.getId().toString();
        String to = target.getId().toString();
        int movedHoldings = 0;
        int mergedHoldings = 0;
        boolean mergedWithSells = false;

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
                if (hasSells(holdingId) && hasSells(keepId)) {
                    mergedWithSells = true;
                }
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
        return new Moved(movedHoldings, mergedHoldings, mergedWithSells, sips, prices, aliases);
    }

    private boolean hasSells(String holdingId) {
        Integer sells = jdbc.queryForObject("SELECT COUNT(*) FROM investment_transactions WHERE holding_id = ? AND type = ?",
                Integer.class, holdingId, "sell");
        return sells != null && sells > 0;
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
