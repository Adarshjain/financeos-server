package com.financeos.domain.statement;

import com.financeos.domain.statement.skip.StatementSkippedPeriodRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Statements are the source of truth: is a date already covered for an account? Covered means a (non-rejected)
 * statement's period holds it, or it lies in a period the owner marked "No statement this period" and is not after the
 * account's latest statement end. Per-account periods are read in two queries and cached briefly, so a batch of
 * alerts does not query per row; {@link #invalidateAll()} drops the cache once a statement has been imported.
 */
@Service
public class StatementCoverage {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private record Range(LocalDate start, LocalDate end) {}

    private record Entry(Instant loadedAt, List<Range> statements, List<Range> skips, LocalDate latestEnd) {}

    private final StatementRepository statementRepository;
    private final StatementSkippedPeriodRepository skipRepository;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public StatementCoverage(StatementRepository statementRepository, StatementSkippedPeriodRepository skipRepository) {
        this.statementRepository = statementRepository;
        this.skipRepository = skipRepository;
    }

    /** The reason the date is covered ("Covered by statement dd/mm/yyyy–dd/mm/yyyy"), empty when it is not. */
    public Optional<String> coveredBy(UUID accountId, LocalDate date) {
        if (accountId == null || date == null) {
            return Optional.empty();
        }
        Entry e = entry(accountId);
        for (Range r : e.statements()) {
            if (!date.isBefore(r.start()) && !date.isAfter(r.end())) {
                return Optional.of("Covered by statement " + label(r));
            }
        }
        if (e.latestEnd() != null && !date.isAfter(e.latestEnd())) {
            for (Range r : e.skips()) {
                if (!date.isBefore(r.start()) && !date.isAfter(r.end())) {
                    return Optional.of("Covered by a no-statement period " + label(r));
                }
            }
        }
        return Optional.empty();
    }

    /** Loads the accounts' periods in two queries, so the per-alert lookups of a batch hit the cache. */
    public void preload(java.util.Collection<UUID> accountIds) {
        List<UUID> missing = accountIds.stream().filter(id -> !fresh(cache.get(id))).distinct().toList();
        if (!missing.isEmpty()) {
            load(missing);
        }
    }

    public void invalidateAll() {
        cache.clear();
    }

    private Entry entry(UUID accountId) {
        Entry e = cache.get(accountId);
        if (!fresh(e)) {
            load(List.of(accountId));
            e = cache.get(accountId);
        }
        return e;
    }

    private static boolean fresh(Entry e) {
        return e != null && e.loadedAt().plus(TTL).isAfter(Instant.now());
    }

    private void load(List<UUID> accountIds) {
        Map<UUID, List<Range>> statements = new java.util.HashMap<>();
        Map<UUID, LocalDate> latest = new java.util.HashMap<>();
        for (StatementRepository.StatementPeriod p : statementRepository.findPeriodsByAccountIdIn(accountIds)) {
            if (p.getVerdict() == StatementVerdict.REJECTED || p.getPeriodEnd() == null) {
                continue;
            }
            latest.merge(p.getAccountId(), p.getPeriodEnd(), (a, b) -> a.isAfter(b) ? a : b);
            if (p.getPeriodStart() != null) {
                statements.computeIfAbsent(p.getAccountId(), k -> new ArrayList<>()).add(new Range(p.getPeriodStart(), p.getPeriodEnd()));
            }
        }
        Map<UUID, List<Range>> skips = new java.util.HashMap<>();
        for (StatementSkippedPeriodRepository.SkippedRange s : skipRepository.findRangesByAccountIds(accountIds)) {
            skips.computeIfAbsent(s.getAccountId(), k -> new ArrayList<>()).add(new Range(s.getPeriodStart(), s.getPeriodEnd()));
        }
        Instant now = Instant.now();
        for (UUID id : accountIds) {
            cache.put(id, new Entry(now, statements.getOrDefault(id, List.of()), skips.getOrDefault(id, List.of()), latest.get(id)));
        }
    }

    private static String label(Range r) {
        return DMY.format(r.start()) + "–" + DMY.format(r.end());
    }
}
