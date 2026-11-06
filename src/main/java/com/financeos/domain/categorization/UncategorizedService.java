package com.financeos.domain.categorization;

import com.financeos.api.transaction.dto.ApplyUncategorizedRequest;
import com.financeos.api.transaction.dto.ApplyUncategorizedResponse;
import com.financeos.api.transaction.dto.CategoryCountsResponse;
import com.financeos.api.transaction.dto.CategoryPick;
import com.financeos.api.transaction.dto.UncategorizedGroupResponse;
import com.financeos.api.transaction.dto.UncategorizedSummaryResponse;
import com.financeos.api.transaction.dto.UncategorizedTransactionResponse;
import com.financeos.api.transaction.dto.UndoUncategorizedRequest;
import com.financeos.api.transaction.dto.UndoUncategorizedResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.categorization.builtin.BuiltinMatch;
import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategoryWriter;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Sort them": the user's rows without a category, grouped by who was paid (the engine's counterparty name and
 * the direction), and the one call that gives a chosen set of them a category and optionally saves a rule. One
 * query loads the user's uncategorised rows (light projection) and the grouping is done in memory; the engine runs
 * once per distinct text, so repeated descriptions cost nothing extra.
 */
@Service
public class UncategorizedService {

    private static final Logger log = LoggerFactory.getLogger(UncategorizedService.class);

    static final int TOP_GROUPS = 3;
    static final int RECENT_LIMIT = 4;
    /** Recent picks are read this deep before direction filtering. */
    private static final int RECENT_SCAN = 40;
    private static final int APPLY_CHUNK = 500;
    /** How many distinct (text, direction, mcc) analyses the memo keeps; ~100 bytes of key each. */
    static final int MEMO_MAX = 50_000;
    /** Users whose group index is kept; the least recently used is dropped beyond this. */
    static final int INDEX_MAX_USERS = 100;
    static final java.time.Duration INDEX_TTL = java.time.Duration.ofMinutes(10);
    /**
     * Total transaction ids the cache may hold across users. Ids are stored as two longs (16 B each), so the worst
     * case is ~4 MB of ids plus a few hundred bytes of overhead per group; the heap is only 512 MB. A user above
     * this alone is served uncached.
     */
    static final long INDEX_MAX_ROWS = 250_000;
    static final long JOIN_TIMEOUT_MILLIS = 5_000;
    /** Cold analysis of at least this many distinct lines runs on the common pool. */
    private static final int PARALLEL_FROM = 400;

    private final TransactionRepository transactionRepository;
    private final CategoryAssigner assigner;
    private final CategoryCatalog catalog;
    private final TransactionCategoryWriter writer;
    private final CategoryRuleRepository ruleRepository;
    private final CategorizationService categorizationService;
    private final UserRepository userRepository;
    /** Runs the rule insert in its own transaction so a duplicate-key failure cannot roll back the category writes. */
    private final org.springframework.transaction.support.TransactionTemplate isolatedTx;

    /**
     * Least-recently-used memo of the engine's analysis (about 0.2 ms each, ~4 s for 20k distinct lines).
     * The analysis is a pure function of its key (text, direction, mcc): the engine reads no rules, links
     * or user data, so entries are the same for every user and sharing them across users leaks nothing
     * (a user only ever gets the analysis of text they sent themselves). Bounded, and thread-safe: the
     * synchronized wrapper also covers the access-order reordering done by a read.
     */
    private final Map<TextKey, Analysis> memo = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TextKey, Analysis> eldest) {
                    return size() > MEMO_MAX;
                }
            });

    /** Without a transaction manager (plain unit tests) the rule insert joins the caller's transaction. */
    public UncategorizedService(TransactionRepository transactionRepository, CategoryAssigner assigner,
                                CategoryCatalog catalog, TransactionCategoryWriter writer,
                                CategoryRuleRepository ruleRepository, CategorizationService categorizationService,
                                UserRepository userRepository) {
        this(transactionRepository, assigner, catalog, writer, ruleRepository, categorizationService,
                userRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UncategorizedService(TransactionRepository transactionRepository, CategoryAssigner assigner,
                                CategoryCatalog catalog, TransactionCategoryWriter writer,
                                CategoryRuleRepository ruleRepository, CategorizationService categorizationService,
                                UserRepository userRepository,
                                org.springframework.transaction.PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            this.isolatedTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            this.isolatedTx.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.isolatedTx = null;
        }
        this.transactionRepository = transactionRepository;
        this.assigner = assigner;
        this.catalog = catalog;
        this.writer = writer;
        this.ruleRepository = ruleRepository;
        this.categorizationService = categorizationService;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------ recent picks

    /** Up to four distinct category/sub pairs the user picked themselves for this direction, newest pick first. */
    @Transactional(readOnly = true)
    public List<CategoryPick> recentPicks(UUID userId, TransactionType type) {
        List<CategoryPick> out = new ArrayList<>();
        for (TransactionRepository.CategoryPairRow row : transactionRepository.findRecentUserCategories(
                userId, type, PageRequest.of(0, RECENT_SCAN))) {
            if (catalog.fits(type, row.getCategoryKey(), row.getSubcategoryKey())) {
                out.add(new CategoryPick(row.getCategoryKey(), row.getSubcategoryKey()));
                if (out.size() == RECENT_LIMIT) {
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ counts for the filter sheet

    @Transactional(readOnly = true)
    public CategoryCountsResponse categoryCounts(UUID userId) {
        long uncategorized = 0;
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, List<CategoryCountsResponse.SubcategoryCount>> subs = new HashMap<>();
        for (TransactionRepository.CategoryCountRow row : transactionRepository.countByCategory(userId)) {
            if (row.getCategoryKey() == null) {
                uncategorized += row.getCount();
                continue;
            }
            totals.merge(row.getCategoryKey(), row.getCount(), Long::sum);
            if (row.getSubcategoryKey() != null) {
                subs.computeIfAbsent(row.getCategoryKey(), k -> new ArrayList<>())
                        .add(new CategoryCountsResponse.SubcategoryCount(row.getSubcategoryKey(), row.getCount()));
            }
        }
        List<CategoryCountsResponse.CategoryCount> categories = new ArrayList<>();
        totals.forEach((key, count) -> {
            List<CategoryCountsResponse.SubcategoryCount> list = subs.getOrDefault(key, new ArrayList<>());
            list.sort(Comparator.comparingLong(CategoryCountsResponse.SubcategoryCount::count).reversed()
                    .thenComparing(CategoryCountsResponse.SubcategoryCount::subcategoryKey));
            categories.add(new CategoryCountsResponse.CategoryCount(key, count, list));
        });
        categories.sort(Comparator.comparingLong(CategoryCountsResponse.CategoryCount::count).reversed()
                .thenComparing(CategoryCountsResponse.CategoryCount::categoryKey));
        return new CategoryCountsResponse(uncategorized, categories);
    }

    // ------------------------------------------------------------------ grouping

    private static final class Group {
        final String key;
        final String label;
        final String direction;
        boolean person;
        long count;
        BigDecimal total = BigDecimal.ZERO;
        LocalDate firstDate;
        LocalDate lastDate;
        /** Only while building; replaced by {@link #sortedIds}. */
        List<TransactionRepository.UncategorizedRow> rows = new ArrayList<>();
        /** The group's transaction ids, newest first (date, then id), as (mostSigBits, leastSigBits) pairs; fixed once built. */
        long[] sortedIds = new long[0];

        Group(String key, String label, String direction) {
            this.key = key;
            this.label = label;
            this.direction = direction;
        }
    }

    private record Analysis(String basis, String label, boolean person) {
    }

    private record TextKey(String text, TransactionType type, String mcc) {
    }

    /** What the uncategorised set looked like when an index was built; any add, remove or edit moves it. */
    private record Fingerprint(long count, Instant maxUpdatedAt) {
    }

    /** Every group of one user, in the order Sort them lists them, with the rows reduced to ids. */
    private static final class GroupIndex {
        final Fingerprint fingerprint;
        final long builtAtMillis = System.currentTimeMillis();
        /** Order in which builds started; an older build never replaces a newer one in the cache. */
        final long seq;
        /** Groups of two or more by count then total, then the one-offs newest first. */
        final List<Group> ordered;
        final int multiCount;
        final long rowCount;
        final Map<String, Group> byKey;

        GroupIndex(long seq, Fingerprint fingerprint, List<Group> ordered, int multiCount, long rowCount) {
            this.seq = seq;
            this.fingerprint = fingerprint;
            this.ordered = ordered;
            this.multiCount = multiCount;
            this.rowCount = rowCount;
            Map<String, Group> m = new HashMap<>();
            ordered.forEach(g -> m.put(g.key, g));
            this.byKey = m;
        }
    }

    /**
     * Per-user cache of the group index, least recently used out beyond {@link #INDEX_MAX_USERS} and dropped after
     * {@link #INDEX_TTL}. Every call checks the entry against a cheap freshness probe (count and newest update of the
     * user's uncategorised rows, one index-only query), so a categorisation, an ingest, a rule run or an edit made by
     * any code path or any instance shows on the next call without each write path having to invalidate anything.
     */
    private final Map<UUID, GroupIndex> indexes = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, GroupIndex> eldest) {
                    return size() > INDEX_MAX_USERS;
                }
            });

    // tunable in tests
    long indexTtlMillis = INDEX_TTL.toMillis();
    long maxCachedRows = INDEX_MAX_ROWS;
    long joinTimeoutMillis = JOIN_TIMEOUT_MILLIS;
    private final java.util.concurrent.atomic.AtomicLong buildSeq = new java.util.concurrent.atomic.AtomicLong();

    int cachedUsers() {
        return indexes.size();
    }

    private final Map<UUID, InFlight> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

    /** Small, bounded: two workers, eight waiting; beyond that a prewarm is dropped. */
    private final java.util.concurrent.ThreadPoolExecutor prewarmPool = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "sort-them-prewarm");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    {
        prewarmPool.allowCoreThreadTimeOut(true);
    }

    private GroupIndex index(UUID userId) {
        TransactionRepository.UncategorizedFingerprint probe = transactionRepository.uncategorizedFingerprint(userId);
        if (probe == null) {
            return build(userId, null);
        }
        Fingerprint current = new Fingerprint(probe.getCount(), probe.getMaxUpdatedAt());
        GroupIndex cached = indexes.get(userId);
        if (cached != null && current.equals(cached.fingerprint)
                && System.currentTimeMillis() - cached.builtAtMillis < indexTtlMillis) {
            return cached;
        }
        return buildShared(userId, current);
    }

    private record InFlight(Fingerprint fingerprint, java.util.concurrent.CompletableFuture<GroupIndex> future) {
    }

    /** One build at a time per user and fingerprint: a concurrent caller waits for the running build instead of repeating it. */
    private GroupIndex buildShared(UUID userId, Fingerprint current) {
        boolean[] mine = {false};
        InFlight flight = inFlight.compute(userId, (k, existing) -> {
            if (existing != null && existing.fingerprint().equals(current)) {
                return existing;
            }
            mine[0] = true;
            return new InFlight(current, new java.util.concurrent.CompletableFuture<>());
        });
        if (!mine[0]) {
            try {
                return flight.future().get(joinTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // the other build failed or is taking too long: build on our own rather than hold this request
                return build(userId, current);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return build(userId, current);
            }
        }
        try {
            GroupIndex built = build(userId, current);
            remember(userId, built);
            flight.future().complete(built);
            return built;
        } catch (RuntimeException | Error e) {
            flight.future().completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(userId, flight);
        }
    }

    /**
     * Keeps a built index unless a newer-started build already stored one; drops expired entries and, beyond the
     * row-id budget, the least recently used ones.
     */
    private void remember(UUID userId, GroupIndex built) {
        synchronized (indexes) {
            if (built.rowCount > maxCachedRows) {
                // one user alone over the budget: serve uncached instead of evicting everyone else
                GroupIndex old = indexes.get(userId);
                if (old != null && old.seq < built.seq) {
                    indexes.remove(userId);
                }
                return;
            }
            long now = System.currentTimeMillis();
            indexes.values().removeIf(e -> now - e.builtAtMillis >= indexTtlMillis);
            GroupIndex current = indexes.get(userId);
            if (current == null || current.seq < built.seq) {
                indexes.put(userId, built);
            }
            long total = 0;
            for (GroupIndex e : indexes.values()) {
                total += e.rowCount;
            }
            var it = indexes.entrySet().iterator();
            while (total > maxCachedRows && it.hasNext()) {
                var eldest = it.next();
                total -= eldest.getValue().rowCount;
                it.remove();
            }
        }
    }

    /**
     * Builds the user's group index in the background when it is not fresh, so a later Sort them call is warm.
     * Never blocks and never throws: a full queue skips it, a failed build is logged.
     */
    public void prewarm(UUID userId) {
        try {
            prewarmPool.execute(() -> {
                try {
                    index(userId);
                } catch (RuntimeException | Error e) {
                    log.warn("Sort them prewarm failed for a user: {}", e.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("Sort them prewarm skipped, queue full");
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdownPrewarm() {
        prewarmPool.shutdownNow();
    }

    private GroupIndex build(UUID userId, Fingerprint fingerprint) {
        long seq = buildSeq.incrementAndGet();
        long started = System.nanoTime();
        List<TransactionRepository.UncategorizedRow> rows = transactionRepository.findUncategorizedRows(userId);
        Map<TextKey, Analysis> analysed = analyseAll(rows);
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (TransactionRepository.UncategorizedRow row : rows) {
            TransactionType type = row.getType();
            Analysis a = analysed.get(new TextKey(textOf(row), type, row.getMcc()));
            String direction = type == TransactionType.CREDIT ? "IN" : "OUT";
            String key = groupKey(a.basis(), direction);
            Group g = byKey.computeIfAbsent(key, k -> new Group(k, a.label(), direction));
            g.person |= a.person();
            g.count++;
            g.total = g.total.add(row.getAmount() == null ? BigDecimal.ZERO : row.getAmount());
            LocalDate d = row.getDate();
            if (d != null) {
                if (g.firstDate == null || d.isBefore(g.firstDate)) {
                    g.firstDate = d;
                }
                if (g.lastDate == null || d.isAfter(g.lastDate)) {
                    g.lastDate = d;
                }
            }
            g.rows.add(row);
        }
        List<Group> multi = new ArrayList<>();
        List<Group> oneOffs = new ArrayList<>();
        for (Group g : byKey.values()) {
            g.rows.sort(Comparator.comparing(TransactionRepository.UncategorizedRow::getDate,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(
                    TransactionRepository.UncategorizedRow::getId, Comparator.reverseOrder()));
            long[] packed = new long[g.rows.size() * 2];
            int i = 0;
            for (TransactionRepository.UncategorizedRow row : g.rows) {
                UUID id = row.getId();
                packed[i++] = id.getMostSignificantBits();
                packed[i++] = id.getLeastSignificantBits();
            }
            g.sortedIds = packed;
            g.rows = null;
            (g.count >= 2 ? multi : oneOffs).add(g);
        }
        multi.sort(GROUP_ORDER);
        oneOffs.sort(ONE_OFF_ORDER);
        List<Group> ordered = new ArrayList<>(multi.size() + oneOffs.size());
        ordered.addAll(multi);
        ordered.addAll(oneOffs);
        log.debug("Grouped {} uncategorised row(s) into {} group(s) in {} ms", rows.size(), ordered.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new GroupIndex(seq, fingerprint, ordered, multi.size(), rows.size());
    }

    private static String textOf(TransactionRepository.UncategorizedRow row) {
        return row.getSourcedDescription() != null && !row.getSourcedDescription().isBlank()
                ? row.getSourcedDescription() : row.getDescription();
    }

    /**
     * The analysis of every distinct (text, direction, mcc) in the rows. Lines not in the memo yet are analysed across
     * cores when there are many, then remembered in row order so the memo's recency stays deterministic.
     */
    private Map<TextKey, Analysis> analyseAll(List<TransactionRepository.UncategorizedRow> rows) {
        Set<TextKey> distinct = new java.util.LinkedHashSet<>();
        for (TransactionRepository.UncategorizedRow row : rows) {
            distinct.add(new TextKey(textOf(row), row.getType(), row.getMcc()));
        }
        Map<TextKey, Analysis> out = new HashMap<>(distinct.size() * 2);
        List<TextKey> missing = new ArrayList<>();
        for (TextKey k : distinct) {
            Analysis hit = memo.get(k);
            if (hit != null) {
                out.put(k, hit);
            } else {
                missing.add(k);
            }
        }
        Map<TextKey, Analysis> computed = new java.util.concurrent.ConcurrentHashMap<>();
        (missing.size() >= PARALLEL_FROM ? missing.parallelStream() : missing.stream())
                .forEach(k -> computed.put(k, analyse(k)));
        for (TextKey k : missing) {
            Analysis a = computed.get(k);
            memo.put(k, a);
            out.put(k, a);
        }
        return out;
    }

    private Analysis analyse(TextKey k) {
        BuiltinMatch m = assigner.analyse(k.text(), k.type(), null, k.mcc());
        String name = m.counterpartyName();
        if (name != null && !name.isBlank()) {
            return new Analysis(name, titleCase(name), m.person());
        }
        String raw = k.text() == null ? "" : k.text().trim();
        if (raw.isEmpty()) {
            return new Analysis("", "No description", false);
        }
        return new Analysis(raw.toLowerCase(Locale.ROOT), raw, m.person());
    }

    private static String titleCase(String lower) {
        StringBuilder sb = new StringBuilder(lower.length());
        boolean start = true;
        for (char c : lower.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ';
        }
        return sb.toString();
    }

    /** Stable for a payee name and direction: hex of the first 10 bytes of SHA-256, safe in a URL. */
    static String groupKey(String basis, String direction) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((basis + "|" + direction).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Comparator<Group> GROUP_ORDER = Comparator.<Group>comparingLong(g -> -g.count)
            .thenComparing((Group g) -> g.total, Comparator.reverseOrder())
            .thenComparing(g -> g.label.toLowerCase(Locale.ROOT)).thenComparing(g -> g.key);
    private static final Comparator<Group> ONE_OFF_ORDER = Comparator
            .comparing((Group g) -> g.lastDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(g -> g.key);

    /** True when {@link #summary} would count at least one row; one probe query, no grouping. */
    @Transactional(readOnly = true)
    public boolean exists(UUID userId) {
        return transactionRepository.existsByUser_IdAndCategoryKeyIsNull(userId);
    }

    @Transactional(readOnly = true)
    public UncategorizedSummaryResponse summary(UUID userId) {
        GroupIndex idx = index(userId);
        List<UncategorizedSummaryResponse.TopGroup> top = idx.ordered.stream().limit(Math.min(TOP_GROUPS, idx.multiCount))
                .map(g -> new UncategorizedSummaryResponse.TopGroup(g.label, g.count)).toList();
        return new UncategorizedSummaryResponse(idx.rowCount, idx.multiCount, top);
    }

    @Transactional(readOnly = true)
    public Page<UncategorizedGroupResponse> groupPage(UUID userId, Pageable pageable) {
        return groupPage(userId, null, pageable);
    }

    /**
     * Groups of two or more by count then total, then the one-offs newest first; one page of that sequence. With
     * {@code q} (case-insensitive, contains, on the group label) only matching groups are listed and counted.
     */
    @Transactional(readOnly = true)
    public Page<UncategorizedGroupResponse> groupPage(UUID userId, String q, Pageable pageable) {
        List<Group> ordered = index(userId).ordered;
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            ordered = ordered.stream().filter(g -> g.label.toLowerCase(Locale.ROOT).contains(needle)).toList();
        }
        int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), ordered.size());
        int to = Math.min(from + pageable.getPageSize(), ordered.size());
        List<UncategorizedGroupResponse> content = ordered.subList(from, to).stream()
                .map(g -> new UncategorizedGroupResponse(g.key, g.label, g.person, g.direction, g.count, g.total,
                        g.firstDate, g.lastDate, g.count == 1))
                .toList();
        return new PageImpl<>(content, pageable, ordered.size());
    }

    /** One group's transactions, newest first. 404 when the key matches no group of the user's right now. */
    @Transactional(readOnly = true)
    public Page<UncategorizedTransactionResponse> groupTransactions(UUID userId, String groupKey, Pageable pageable) {
        Group group = index(userId).byKey.get(groupKey);
        if (group == null) {
            throw new ResourceNotFoundException("Group", groupKey);
        }
        long[] packed = group.sortedIds;
        int total = packed.length / 2;
        int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), total);
        int to = Math.min(from + pageable.getPageSize(), total);
        List<UUID> ids = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            ids.add(new UUID(packed[2 * i], packed[2 * i + 1]));
        }
        Map<UUID, TransactionRepository.GroupTransactionRow> details = new HashMap<>();
        if (!ids.isEmpty()) {
            for (TransactionRepository.GroupTransactionRow r : transactionRepository.findGroupTransactionRows(userId, ids)) {
                details.put(r.getId(), r);
            }
        }
        List<UncategorizedTransactionResponse> content = new ArrayList<>();
        for (UUID id : ids) {
            TransactionRepository.GroupTransactionRow r = details.get(id);
            if (r == null) {
                continue;
            }
            // A foreign charge waiting for its statement has no rupee amount: it stays null.
            BigDecimal signed = r.getAmount() == null ? null
                    : r.getType() == TransactionType.DEBIT ? r.getAmount().negate() : r.getAmount();
            content.add(new UncategorizedTransactionResponse(r.getId(), r.getDate(), r.getDescription(),
                    r.getSourcedDescription(), r.getAccountId(), r.getAccountName(), r.getAccountLast4(),
                    r.getCardholderName(), r.getCardLast4(), r.getSource(), r.getChannel(), signed, r.getType()));
        }
        return new PageImpl<>(content, pageable, total);
    }

    // ------------------------------------------------------------------ apply

    /**
     * Gives each owned, still-uncategorised transaction the user's pick (source USER), skipping foreign ids,
     * rows that already have a category and rows whose direction the category does not fit. With a rule, creates it
     * or re-points the user's existing rule with the same pattern and match type; never applies it to past matches.
     */
    @Transactional
    public ApplyUncategorizedResponse apply(UUID userId, ApplyUncategorizedRequest request) {
        catalog.validateKeys(request.categoryKey(), request.subcategoryKey());
        MatchType matchType = null;
        String canonicalPattern = null;
        if (request.rule() != null) {
            matchType = parseMatchType(request.rule().matchType());
            canonicalPattern = RuleMatcher.canonicalizePattern(matchType, request.rule().pattern());
        }

        Set<UUID> wanted = new HashSet<>(request.transactionIds());
        List<UUID> ids = new ArrayList<>(wanted);
        List<Transaction> toSave = new ArrayList<>();
        List<UUID> userCleared = new ArrayList<>();
        Set<UUID> found = new HashSet<>();
        int skipped = 0;
        for (int from = 0; from < ids.size(); from += APPLY_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(from + APPLY_CHUNK, ids.size()));
            for (Transaction t : transactionRepository.findAllByIdInAndUserId(chunk, userId)) {
                found.add(t.getId());
                boolean wasUserCleared = t.getCategorySource() == CategorySource.USER;
                if (t.getCategoryKey() == null && writer.fits(t, request.categoryKey(), request.subcategoryKey())
                        && writer.set(t, request.categoryKey(), request.subcategoryKey(), CategorySource.USER, (UUID) null)) {
                    toSave.add(t);
                    if (wasUserCleared) {
                        userCleared.add(t.getId());
                    }
                } else {
                    skipped++;
                }
            }
        }
        skipped += wanted.size() - found.size();
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }

        RuleOutcome rule = request.rule() == null ? null : upsertRule(userId, canonicalPattern, matchType, request);
        List<UUID> updatedIds = toSave.stream().map(Transaction::getId).toList();
        return new ApplyUncategorizedResponse(toSave.size(), skipped, rule == null ? null : rule.id(), updatedIds,
                rule != null && rule.created(), rule == null ? null : rule.previousCategoryKey(),
                rule == null ? null : rule.previousSubcategoryKey(), userCleared,
                rule == null ? null : canonicalPattern, rule == null ? null : matchType.name());
    }

    /** What the rule step did: the rule, whether it is new, and the target an existing one had before. */
    private record RuleOutcome(UUID id, boolean created, String previousCategoryKey, String previousSubcategoryKey) {
    }

    private RuleOutcome upsertRule(UUID userId, String canonicalPattern, MatchType matchType, ApplyUncategorizedRequest request) {
        CategoryRule existing = ruleRepository.findByUserIdAndMerchantKeyAndMatchType(userId, canonicalPattern, matchType)
                .orElse(null);
        if (existing == null) {
            UUID inserted = insertRule(userId, canonicalPattern, matchType, request);
            if (inserted != null) {
                return new RuleOutcome(inserted, true, null, null);
            }
            // A concurrent request created the same rule first: re-read it and update that one.
            existing = ruleRepository.findByUserIdAndMerchantKeyAndMatchType(userId, canonicalPattern, matchType)
                    .orElseThrow(() -> new ValidationException("The rule could not be saved, try again"));
        }
        String previousCategory = existing.getCategoryKey();
        String previousSub = existing.getSubcategoryKey();
        return new RuleOutcome(updateRule(existing, request), false, previousCategory, previousSub);
    }

    /** The new rule's id, or null when the (user, pattern, match type) already exists. */
    private UUID insertRule(UUID userId, String canonicalPattern, MatchType matchType, ApplyUncategorizedRequest request) {
        try {
            if (isolatedTx == null) {
                return buildAndSaveRule(userId, canonicalPattern, matchType, request);
            }
            return isolatedTx.execute(status -> buildAndSaveRule(userId, canonicalPattern, matchType, request));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return null;
        }
    }

    private UUID buildAndSaveRule(UUID userId, String canonicalPattern, MatchType matchType, ApplyUncategorizedRequest request) {
        CategoryRule rule = new CategoryRule();
        rule.setUser(userRepository.getReferenceById(userId));
        rule.setMerchantKey(canonicalPattern);
        rule.setMatchType(matchType);
        rule.setDisplayName(request.rule().pattern().trim());
        rule.setCategoryKey(request.categoryKey());
        rule.setSubcategoryKey(request.subcategoryKey());
        return ruleRepository.saveAndFlush(rule).getId();
    }

    private UUID updateRule(CategoryRule existing, ApplyUncategorizedRequest request) {
        boolean changed = !request.categoryKey().equals(existing.getCategoryKey())
                || !java.util.Objects.equals(request.subcategoryKey(), existing.getSubcategoryKey());
        if (changed) {
            existing.setCategoryKey(request.categoryKey());
            existing.setSubcategoryKey(request.subcategoryKey());
            // keeps only the rows this rule already governs in step; past matches are left alone
            categorizationService.saveEditedRule(existing, false, true);
        }
        return existing.getId();
    }

    // ------------------------------------------------------------------ undo

    /**
     * Takes back an apply: the user's rows in {@code transactionIds} that still carry exactly the pick that apply gave
     * them (as USER) go back to no category; a rule apply created is deleted, one it re-pointed gets its previous target
     * back. A rule the user has edited since (its target is no longer the pick) is left alone.
     */
    @Transactional
    public UndoUncategorizedResponse undo(UUID userId, UndoUncategorizedRequest request) {
        boolean restoresRule = request.ruleId() != null && !request.ruleCreated();
        if (restoresRule && (request.previousRuleCategoryKey() != null || request.previousRuleSubcategoryKey() != null)) {
            catalog.validateKeys(request.previousRuleCategoryKey(), request.previousRuleSubcategoryKey());
        }
        Set<UUID> userCleared = request.userClearedIds() == null ? Set.of() : new HashSet<>(request.userClearedIds());
        List<UUID> ids = new ArrayList<>(new HashSet<>(request.transactionIds()));
        List<Transaction> toSave = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += APPLY_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(from + APPLY_CHUNK, ids.size()));
            for (Transaction t : transactionRepository.findAllByIdInAndUserId(chunk, userId)) {
                if (request.categoryKey().equals(t.getCategoryKey())
                        && java.util.Objects.equals(request.subcategoryKey(), t.getSubcategoryKey())
                        && t.getCategorySource() == CategorySource.USER) {
                    boolean hadCleared = userCleared.contains(t.getId());
                    // a row the user had cleared by hand goes back to that state, any other to never-categorised
                    writer.restore(t, null, null, hadCleared ? CategorySource.USER : null, null,
                            hadCleared ? AppTime.nowInstant() : null);
                    toSave.add(t);
                }
            }
        }
        int skipped = ids.size() - toSave.size();
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }

        boolean deleted = false;
        boolean restored = false;
        if (request.ruleId() != null) {
            CategoryRule rule = ruleRepository.findByUserIdAndIdIn(userId, List.of(request.ruleId())).stream()
                    .findFirst().orElse(null);
            boolean untouched = rule != null && request.categoryKey().equals(rule.getCategoryKey())
                    && java.util.Objects.equals(request.subcategoryKey(), rule.getSubcategoryKey())
                    && (request.ruleMerchantKey() == null || request.ruleMerchantKey().equals(rule.getMerchantKey()))
                    && (request.ruleMatchType() == null || request.ruleMatchType().equalsIgnoreCase(rule.getMatchType().name()));
            if (untouched && request.ruleCreated()) {
                categorizationService.deleteRule(rule);
                deleted = true;
            } else if (untouched && request.previousRuleCategoryKey() != null) {
                rule.setCategoryKey(request.previousRuleCategoryKey());
                rule.setSubcategoryKey(request.previousRuleSubcategoryKey());
                categorizationService.saveEditedRule(rule, false, true);
                restored = true;
            }
        }
        return new UndoUncategorizedResponse(toSave.size(), skipped, deleted, restored);
    }

    private static MatchType parseMatchType(String value) {
        if (value == null || value.isBlank()) {
            return MatchType.CONTAINS;
        }
        try {
            return MatchType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown match type: " + value);
        }
    }
}
