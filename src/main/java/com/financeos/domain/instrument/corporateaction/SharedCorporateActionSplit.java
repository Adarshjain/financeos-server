package com.financeos.domain.instrument.corporateaction;

import com.financeos.domain.investment.DayChange;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The data step of V100 (corporate actions become per user): every shared corporate action (one with
 * no {@code user_id}) is copied, with a new id, to each user it affects, and the shared row is then
 * deleted; a shared row that affects nobody is just deleted. Plain JDBC on portable SQL, so the same
 * code runs in the Flyway migration (Oracle) and in tests (H2).
 *
 * <p>A user is a candidate for a row when they hold the action's instrument (for a demerger or merger:
 * the parent / transferor) or received shares of it from one of their own earlier demergers / mergers
 * (a demerger child / merger acquirer they have no holding row of yet: the old model created the
 * child holding only for whoever entered the demerger, and their seeded shares appear — with every
 * later action on the child applied — as soon as a trade adds the holding). A candidate is affected
 * when
 * <ul>
 *   <li>they traded it on or before the ex-date (the lot engine counts a parent bought ON a demerger's
 *       ex-date into the seeded child shares, so "on" is included), or</li>
 *   <li>they received it on or before the ex-date from an earlier (or same-day) demerger / merger of
 *       theirs, or</li>
 *   <li>the ex-date is recent or still ahead — no earlier than today minus the day-change window
 *       ({@link DayChange#CURRENT_WITHIN_DAYS} + {@link DayChange#PREVIOUS_WITHIN_DAYS} days): a split or
 *       bonus there rescales the previous close every current holder's day change compares against,
 *       and a future one will apply to every current holder, or</li>
 *   <li>the row is a merger and they hold the transferor at all: the engine labels every holding of a
 *       merged transferor "Merged into …" (and lists the merger in its history) whenever they bought
 *       it, so every current holder keeps that.</li>
 * </ul>
 * Otherwise the user held the instrument only after the ex-date and gets no copy: it never applied to
 * their lots (the engine applies an action to the lots open at its ex-date), so their figures are
 * unchanged; only a no-op line ("Split 1:2", quantity 0 → 0) leaves their position's history.
 *
 * <p>Rows are processed by ex-date; within one ex-date the rows are repeated until no copy is added,
 * so a demerger / merger seeding an instrument reaches a same-day split or bonus on it whatever the row
 * order (the engine applies seeds before actions on the same date).
 *
 * <p>Each copy keeps every column of the shared row, the per-person {@code fractional_cash_in_lieu}
 * included, and its {@code created_at} (copied in SQL, so no time zone is involved). No other table
 * stores a corporate-action id (the engine's seeded lots and cash-in-lieu are derived at read time),
 * so there are no references to remap.
 *
 * <p>Idempotent: only shared rows are read; copy ids are derived from (shared row id, user id), so a
 * re-run after a partial run inserts no duplicates; seeds already copied to users count as theirs.
 * Holdings without an owner hold nothing for anyone and are ignored.
 */
public final class SharedCorporateActionSplit {

    /** What the split did. */
    public record Result(int sharedRows, int copiesInserted, int rowsDropped, int sharedRowsWithCashInLieu,
                         int copiesWithCashInLieu) {
    }

    private record SharedRow(String id, String instrumentId, String targetInstrumentId, String type,
                             LocalDate exDate, boolean hasCashInLieu) {
        boolean seeds() {
            return targetInstrumentId != null && ("demerger".equals(type) || "merger".equals(type));
        }
    }

    private SharedCorporateActionSplit() {
    }

    /** The id of {@code userId}'s copy of shared row {@code sharedId} (stable across runs). */
    public static String copyId(String sharedId, String userId) {
        return UUID.nameUUIDFromBytes(("corporate_actions:V100:" + sharedId + ":" + userId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static Result run(Connection c, LocalDate today) throws SQLException {
        List<SharedRow> shared = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, instrument_id, target_instrument_id, type, ex_date, fractional_cash_in_lieu "
                        + "FROM corporate_actions WHERE user_id IS NULL ORDER BY ex_date, id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                shared.add(new SharedRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), rs.getBigDecimal(6) != null));
            }
        }

        // instrument -> (user -> the earliest date shares were seeded into it by the user's own demergers /
        // mergers): from rows already owned (a partial earlier run's copies) and from the copies made below.
        Map<String, Map<String, LocalDate>> seededOn = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id, target_instrument_id, ex_date FROM corporate_actions WHERE user_id IS NOT NULL "
                        + "AND target_instrument_id IS NOT NULL AND type IN ('demerger', 'merger')");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                seed(seededOn, rs.getString(2), rs.getString(1), rs.getObject(3, LocalDate.class));
            }
        }

        LocalDate recentFrom = today.minusDays(DayChange.CURRENT_WITHIN_DAYS + DayChange.PREVIOUS_WITHIN_DAYS);
        Map<String, Map<String, LocalDate>> holdersCache = new HashMap<>();
        int copies = 0;
        int dropped = 0;
        int sharedWithCash = 0;
        int copiesWithCash = 0;
        int i = 0;
        while (i < shared.size()) {
            // One ex-date at a time; within it, repeat until a pass adds no copy (a same-day seed then
            // reaches a same-day split / bonus whatever the row order).
            LocalDate exDate = shared.get(i).exDate();
            int end = i;
            while (end < shared.size() && shared.get(end).exDate().equals(exDate)) {
                end++;
            }
            List<SharedRow> day = shared.subList(i, end);
            Map<String, TreeSet<String>> copiedTo = new HashMap<>();
            boolean added = true;
            while (added) {
                added = false;
                for (SharedRow row : day) {
                    TreeSet<String> done = copiedTo.computeIfAbsent(row.id(), k -> new TreeSet<>());
                    Map<String, LocalDate> holders = holdersCache.get(row.instrumentId());
                    if (holders == null) {
                        holders = holders(c, row.instrumentId());
                        holdersCache.put(row.instrumentId(), holders);
                    }
                    Map<String, LocalDate> seeded = seededOn.getOrDefault(row.instrumentId(), Map.of());
                    TreeSet<String> candidates = new TreeSet<>(holders.keySet());
                    candidates.addAll(seeded.keySet());
                    for (String user : candidates) {
                        if (done.contains(user) || !affected(row, holders, seeded, user, recentFrom)) {
                            continue;
                        }
                        done.add(user);
                        added = true;
                        int inserted = copy(c, row.id(), user);
                        copies += inserted;
                        if (row.hasCashInLieu()) {
                            copiesWithCash += inserted;
                        }
                        if (row.seeds()) {
                            seed(seededOn, row.targetInstrumentId(), user, row.exDate());
                        }
                    }
                }
            }
            for (SharedRow row : day) {
                if (row.hasCashInLieu()) {
                    sharedWithCash++;
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM corporate_actions WHERE id = ? AND user_id IS NULL")) {
                    del.setString(1, row.id());
                    del.executeUpdate();
                }
                if (copiedTo.get(row.id()).isEmpty()) {
                    dropped++;
                }
            }
            i = end;
        }
        return new Result(shared.size(), copies, dropped, sharedWithCash, copiesWithCash);
    }

    /** Whether {@code user} (a holder of the row's instrument, or seeded into it) is affected by {@code row}. */
    private static boolean affected(SharedRow row, Map<String, LocalDate> holders, Map<String, LocalDate> seeded,
                                    String user, LocalDate recentFrom) {
        boolean holds = holders.containsKey(user);
        LocalDate firstTrade = holders.get(user);
        LocalDate seededDate = seeded.get(user);
        boolean tradedBefore = firstTrade != null && !firstTrade.isAfter(row.exDate());
        boolean seededBefore = seededDate != null && !seededDate.isAfter(row.exDate());
        boolean recentOrAhead = !row.exDate().isBefore(recentFrom);
        boolean mergerHolder = "merger".equals(row.type()) && holds;
        return tradedBefore || seededBefore || recentOrAhead || mergerHolder;
    }

    private static void seed(Map<String, Map<String, LocalDate>> seededOn, String instrumentId, String userId,
                             LocalDate date) {
        seededOn.computeIfAbsent(instrumentId, k -> new HashMap<>()).merge(userId, date, SharedCorporateActionSplit::min);
    }

    /** Every user holding {@code instrumentId}, with their first trade of it (null when they have none). */
    private static Map<String, LocalDate> holders(Connection c, String instrumentId) throws SQLException {
        Map<String, LocalDate> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT h.user_id, MIN(t.trade_date) FROM holdings h "
                        + "LEFT JOIN investment_transactions t ON t.holding_id = h.id "
                        + "WHERE h.instrument_id = ? AND h.user_id IS NOT NULL GROUP BY h.user_id")) {
            ps.setString(1, instrumentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getObject(2, LocalDate.class));
                }
            }
        }
        return out;
    }

    /** Inserts {@code userId}'s copy of the shared row unless it is already there; 1 when inserted. */
    private static int copy(Connection c, String sharedId, String userId) throws SQLException {
        String id = copyId(sharedId, userId);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO corporate_actions (id, user_id, instrument_id, type, ratio_from, ratio_to, ex_date, notes, "
                        + "created_at, target_instrument_id, cost_allocation_pct, fractional_cash_in_lieu) "
                        + "SELECT ?, ?, s.instrument_id, s.type, s.ratio_from, s.ratio_to, s.ex_date, s.notes, "
                        + "s.created_at, s.target_instrument_id, s.cost_allocation_pct, s.fractional_cash_in_lieu "
                        + "FROM corporate_actions s WHERE s.id = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM corporate_actions x WHERE x.id = ?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, sharedId);
            ps.setString(4, id);
            return ps.executeUpdate();
        }
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
