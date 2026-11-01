package com.financeos.domain.categorization;

import com.financeos.api.transaction.dto.ApplyUncategorizedRequest;
import com.financeos.api.transaction.dto.ApplyUncategorizedResponse;
import com.financeos.api.transaction.dto.CategoryCountsResponse;
import com.financeos.api.transaction.dto.CategoryPick;
import com.financeos.api.transaction.dto.UncategorizedGroupResponse;
import com.financeos.api.transaction.dto.UncategorizedSummaryResponse;
import com.financeos.api.transaction.dto.UncategorizedTransactionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
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

    private final TransactionRepository transactionRepository;
    private final CategoryAssigner assigner;
    private final CategoryCatalog catalog;
    private final TransactionCategoryWriter writer;
    private final CategoryRuleRepository ruleRepository;
    private final CategorizationService categorizationService;
    private final UserRepository userRepository;

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

    public UncategorizedService(TransactionRepository transactionRepository, CategoryAssigner assigner,
                                CategoryCatalog catalog, TransactionCategoryWriter writer,
                                CategoryRuleRepository ruleRepository, CategorizationService categorizationService,
                                UserRepository userRepository) {
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
        final List<TransactionRepository.UncategorizedRow> rows = new ArrayList<>();

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

    private List<Group> groups(UUID userId) {
        long started = System.nanoTime();
        List<TransactionRepository.UncategorizedRow> rows = transactionRepository.findUncategorizedRows(userId);
        Map<TextKey, Analysis> analysed = new HashMap<>();
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (TransactionRepository.UncategorizedRow row : rows) {
            String text = row.getSourcedDescription() != null && !row.getSourcedDescription().isBlank()
                    ? row.getSourcedDescription() : row.getDescription();
            TransactionType type = row.getType();
            Analysis a = analysed.computeIfAbsent(new TextKey(text, type, row.getMcc()), this::memoised);
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
        List<Group> out = new ArrayList<>(byKey.values());
        log.debug("Grouped {} uncategorised row(s) into {} group(s) in {} ms", rows.size(), out.size(),
                (System.nanoTime() - started) / 1_000_000);
        return out;
    }

    private Analysis memoised(TextKey k) {
        Analysis hit = memo.get(k);
        if (hit == null) {
            hit = analyse(k);
            memo.put(k, hit);
        }
        return hit;
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

    @Transactional(readOnly = true)
    public UncategorizedSummaryResponse summary(UUID userId) {
        List<Group> all = groups(userId);
        long count = 0;
        List<Group> multi = new ArrayList<>();
        for (Group g : all) {
            count += g.count;
            if (g.count >= 2) {
                multi.add(g);
            }
        }
        multi.sort(GROUP_ORDER);
        List<UncategorizedSummaryResponse.TopGroup> top = multi.stream().limit(TOP_GROUPS)
                .map(g -> new UncategorizedSummaryResponse.TopGroup(g.label, g.count)).toList();
        return new UncategorizedSummaryResponse(count, multi.size(), top);
    }

    /** Groups of two or more by count then total, then the one-offs newest first; one page of that sequence. */
    @Transactional(readOnly = true)
    public Page<UncategorizedGroupResponse> groupPage(UUID userId, Pageable pageable) {
        List<Group> multi = new ArrayList<>();
        List<Group> oneOffs = new ArrayList<>();
        for (Group g : groups(userId)) {
            (g.count >= 2 ? multi : oneOffs).add(g);
        }
        multi.sort(GROUP_ORDER);
        oneOffs.sort(ONE_OFF_ORDER);
        List<Group> ordered = new ArrayList<>(multi.size() + oneOffs.size());
        ordered.addAll(multi);
        ordered.addAll(oneOffs);
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
        Group group = groups(userId).stream().filter(g -> g.key.equals(groupKey)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Group", groupKey));
        List<TransactionRepository.UncategorizedRow> rows = new ArrayList<>(group.rows);
        rows.sort(Comparator.comparing(TransactionRepository.UncategorizedRow::getDate,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(TransactionRepository.UncategorizedRow::getId,
                Comparator.reverseOrder()));
        int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        List<UUID> ids = rows.subList(from, to).stream().map(TransactionRepository.UncategorizedRow::getId).toList();
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
            BigDecimal signed = r.getType() == TransactionType.DEBIT ? r.getAmount().negate() : r.getAmount();
            content.add(new UncategorizedTransactionResponse(r.getId(), r.getDate(), r.getDescription(),
                    r.getSourcedDescription(), r.getAccountId(), r.getAccountName(), r.getAccountLast4(),
                    r.getCardholderName(), r.getCardLast4(), r.getSource(), r.getChannel(), signed, r.getType()));
        }
        return new PageImpl<>(content, pageable, rows.size());
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
        Set<UUID> found = new HashSet<>();
        int skipped = 0;
        for (int from = 0; from < ids.size(); from += APPLY_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(from + APPLY_CHUNK, ids.size()));
            for (Transaction t : transactionRepository.findAllByIdInAndUserId(chunk, userId)) {
                found.add(t.getId());
                if (t.getCategoryKey() == null && writer.fits(t, request.categoryKey(), request.subcategoryKey())
                        && writer.set(t, request.categoryKey(), request.subcategoryKey(), CategorySource.USER, (UUID) null)) {
                    toSave.add(t);
                } else {
                    skipped++;
                }
            }
        }
        skipped += wanted.size() - found.size();
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }

        UUID ruleId = null;
        if (request.rule() != null) {
            ruleId = upsertRule(userId, canonicalPattern, matchType, request);
        }
        return new ApplyUncategorizedResponse(toSave.size(), skipped, ruleId);
    }

    private UUID upsertRule(UUID userId, String canonicalPattern, MatchType matchType, ApplyUncategorizedRequest request) {
        CategoryRule existing = ruleRepository.findByUserIdAndMerchantKeyAndMatchType(userId, canonicalPattern, matchType)
                .orElse(null);
        if (existing == null) {
            CategoryRule rule = new CategoryRule();
            rule.setUser(userRepository.getReferenceById(userId));
            rule.setMerchantKey(canonicalPattern);
            rule.setMatchType(matchType);
            rule.setDisplayName(request.rule().pattern().trim());
            rule.setCategoryKey(request.categoryKey());
            rule.setSubcategoryKey(request.subcategoryKey());
            return ruleRepository.save(rule).getId();
        }
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
