package com.financeos.domain.recurring;

import com.financeos.api.recurring.dto.RecurringSeriesDto;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.transaction.TransactionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Subscriptions, instalments and other regular payments: detects series from a user's debits, lets the user
 * track or dismiss them, follows tracked ones (auto-link, price changes, two missed cycles end a series).
 * Every query carries the user id.
 */
@Service
public class RecurringService {

    private static final Logger log = LoggerFactory.getLogger(RecurringService.class);

    /** Look-back for detection. */
    static final int HISTORY_MONTHS = 13;
    /** A payment links to a tracked series when within this share of its current amount. */
    static final BigDecimal LINK_TOLERANCE = new BigDecimal("0.25");
    static final int MAX_MISSED_CYCLES = 2;

    /** Debits in these categories are never part of a series (transfers, card bills, money lent). */
    private static final List<String> SKIP_CATEGORIES = List.of("card_bill", "self_transfer", "lent", "repaid");
    /** A merchant with a row in one of these states is never proposed again. */
    private static final Set<RecurringStatus> NEVER_PROPOSE = EnumSet.of(
            RecurringStatus.TRACKED, RecurringStatus.DISMISSED, RecurringStatus.LOAN);

    private static final String DEBITS = "SELECT t.id, t.date, t.amount, COALESCE(t.sourcedDescription, t.description), "
            + "t.categoryKey, t.account.id FROM Transaction t "
            + "WHERE t.user.id = :userId AND t.type = :debit AND t.isTransactionExcluded = false "
            + "AND t.date >= :from AND t.date <= :to "
            + "AND (t.categoryKey IS NULL OR t.categoryKey NOT IN :skip) "
            + "AND NOT EXISTS (SELECT 1 FROM TransactionLinkMember m WHERE m.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanEvent e WHERE e.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanPayment p WHERE p.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanCharge c WHERE c.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM Lending l WHERE l.transaction = t) ";
    private static final String NOT_IN_A_SERIES =
            "AND NOT EXISTS (SELECT 1 FROM RecurringSeriesPayment rp WHERE rp.transactionId = t.id) ";

    @PersistenceContext
    private EntityManager entityManager;

    private final RecurringSeriesRepository seriesRepository;
    private final RecurringSeriesPaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final LoanRepository loanRepository;

    public RecurringService(RecurringSeriesRepository seriesRepository,
                            RecurringSeriesPaymentRepository paymentRepository,
                            AccountRepository accountRepository,
                            LoanRepository loanRepository) {
        this.seriesRepository = seriesRepository;
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.loanRepository = loanRepository;
    }

    // ------------------------------------------------------------------ detection

    /** Finds series in the user's last 13 months of debits and proposes the new ones; returns how many rows were written. */
    @Transactional
    public int detect(UUID userId) {
        LocalDate today = AppTime.today();
        List<RecurringDetector.Payment> debits = loadDebits(userId, today.minusMonths(HISTORY_MONTHS), today, false);
        Map<String, List<RecurringDetector.Payment>> byMerchant = new LinkedHashMap<>();
        Map<UUID, String> keyOf = new HashMap<>();
        for (RecurringDetector.Payment p : debits) {
            String key = RecurringDetector.merchantKey(p.description());
            if (!key.isEmpty()) {
                byMerchant.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
        }
        Map<String, RecurringSeries> existing = seriesRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(RecurringSeries::getMerchantKey, s -> s, (a, b) -> a));

        List<RecurringSeries> toSave = new ArrayList<>();
        Map<RecurringSeries, List<RecurringDetector.Payment>> payments = new LinkedHashMap<>();
        List<RecurringSeries> stale = new ArrayList<>();
        Instant now = AppTime.nowInstant();

        for (Map.Entry<String, List<RecurringDetector.Payment>> e : byMerchant.entrySet()) {
            String key = e.getKey();
            RecurringSeries row = existing.get(key);
            if (row != null && NEVER_PROPOSE.contains(row.getStatus())) {
                continue;
            }
            List<RecurringDetector.Payment> candidates = e.getValue();
            if (row != null && row.getStatus() == RecurringStatus.ENDED) {
                LocalDate endedAfter = row.getLastPaidOn();
                candidates = candidates.stream().filter(p -> p.date().isAfter(endedAfter)).toList();
            }
            Optional<RecurringDetector.Series> found = RecurringDetector.detect(candidates, today, key);
            if (found.isEmpty()) {
                if (row != null && row.getStatus() == RecurringStatus.PROPOSED) {
                    stale.add(row);
                }
                continue;
            }
            RecurringDetector.Series s = found.get();
            RecurringDetector.Payment latest = s.latest();
            if (row == null) {
                row = new RecurringSeries();
                row.setUserId(userId);
                row.setMerchantKey(key);
                row.setCreatedAt(now);
            }
            row.setDisplayName(RecurringDetector.displayName(key));
            row.setKind(s.kind());
            row.setPeriod(s.period());
            row.setAmount(s.amount());
            row.setAccountId(latest.accountId());
            row.setLastPaidOn(latest.date());
            row.setNextDueOn(s.period().next(latest.date()));
            row.setStatus(RecurringStatus.PROPOSED);
            row.setMissedCycles(0);
            row.setLoanId(null);
            row.setUpdatedAt(now);
            toSave.add(row);
            payments.put(row, s.payments());
        }

        // A proposal that no longer holds (its pattern broke or went stale) is withdrawn.
        if (!stale.isEmpty()) {
            List<UUID> staleIds = stale.stream().map(RecurringSeries::getId).toList();
            seriesRepository.deletePaymentsOf(staleIds);
            seriesRepository.deleteAll(stale);
        }
        if (toSave.isEmpty()) {
            return stale.size();
        }
        List<UUID> reused = toSave.stream().map(RecurringSeries::getId).filter(java.util.Objects::nonNull).toList();
        if (!reused.isEmpty()) {
            seriesRepository.deletePaymentsOf(reused);
        }
        seriesRepository.saveAllAndFlush(toSave);
        List<RecurringSeriesPayment> links = new ArrayList<>();
        payments.forEach((series, list) -> list.forEach(p -> links.add(new RecurringSeriesPayment(series.getId(), p.transactionId()))));
        paymentRepository.saveAll(links);
        return toSave.size() + stale.size();
    }

    // ------------------------------------------------------------------ tracking

    /**
     * For every tracked series: links new payments in its cycle window (a new amount within 25% becomes the
     * series amount), advances the due date, and counts a cycle with no payment as missed; the second miss ends it.
     * Own transaction so the daily job and the post-ingest hook never share one with a caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int linkAndAdvance(UUID userId) {
        List<RecurringSeries> tracked = seriesRepository.findByUserIdAndStatusOrderByNextDueOnAsc(userId, RecurringStatus.TRACKED);
        if (tracked.isEmpty()) {
            return 0;
        }
        LocalDate today = AppTime.today();
        LocalDate from = tracked.stream().map(s -> s.getNextDueOn().minusDays(s.getPeriod().windowDays()))
                .min(Comparator.naturalOrder()).orElse(today);
        Map<String, List<RecurringDetector.Payment>> byMerchant = new HashMap<>();
        if (!from.isAfter(today)) {
            for (RecurringDetector.Payment p : loadDebits(userId, from, today, true)) {
                String key = RecurringDetector.merchantKey(p.description());
                if (!key.isEmpty()) {
                    byMerchant.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                }
            }
        }
        Instant now = AppTime.nowInstant();
        List<RecurringSeriesPayment> links = new ArrayList<>();
        int changed = 0;
        for (RecurringSeries series : tracked) {
            List<RecurringDetector.Payment> candidates = byMerchant.getOrDefault(series.getMerchantKey(), List.of()).stream()
                    .sorted(Comparator.comparing(RecurringDetector.Payment::date)).collect(Collectors.toCollection(ArrayList::new));
            if (advance(series, candidates, today, links)) {
                series.setUpdatedAt(now);
                changed++;
            }
        }
        if (changed > 0) {
            seriesRepository.saveAll(tracked);
            paymentRepository.saveAll(links);
        }
        return changed;
    }

    /** Applies links and misses to one series; true when anything changed. */
    private boolean advance(RecurringSeries series, List<RecurringDetector.Payment> candidates, LocalDate today,
                            List<RecurringSeriesPayment> links) {
        boolean changed = false;
        int window = series.getPeriod().windowDays();
        while (true) {
            LocalDate lo = series.getNextDueOn().minusDays(window);
            LocalDate hi = series.getNextDueOn().plusDays(window);
            RecurringDetector.Payment match = null;
            for (RecurringDetector.Payment c : candidates) {
                if (!c.date().isBefore(lo) && !c.date().isAfter(hi) && withinLinkTolerance(series.getAmount(), c.amount())) {
                    match = c;
                    break;
                }
            }
            if (match != null) {
                candidates.remove(match);
                links.add(new RecurringSeriesPayment(series.getId(), match.transactionId()));
                series.setAmount(match.amount());
                series.setLastPaidOn(match.date());
                series.setNextDueOn(series.getPeriod().next(match.date()));
                series.setMissedCycles(0);
                changed = true;
            } else if (today.isAfter(hi)) {
                series.setMissedCycles(series.getMissedCycles() + 1);
                series.setNextDueOn(series.getPeriod().next(series.getNextDueOn()));
                changed = true;
                if (series.getMissedCycles() >= MAX_MISSED_CYCLES) {
                    series.setStatus(RecurringStatus.ENDED);
                    return true;
                }
            } else {
                return changed;
            }
        }
    }

    static boolean withinLinkTolerance(BigDecimal current, BigDecimal candidate) {
        return candidate.subtract(current).abs().compareTo(current.multiply(LINK_TOLERANCE)) <= 0;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<RecurringSeriesDto> proposals(UUID userId) {
        return toDtos(seriesRepository.findByUserIdAndStatusOrderByNextDueOnAsc(userId, RecurringStatus.PROPOSED));
    }

    @Transactional(readOnly = true)
    public List<RecurringSeriesDto> tracked(UUID userId) {
        return toDtos(seriesRepository.findByUserIdAndStatusOrderByNextDueOnAsc(userId, RecurringStatus.TRACKED));
    }

    /** The user's tracked series with their account names, for Upcoming. */
    @Transactional(readOnly = true)
    public List<TrackedRow> trackedRows(UUID userId) {
        List<RecurringSeries> rows = seriesRepository.findByUserIdAndStatusOrderByNextDueOnAsc(userId, RecurringStatus.TRACKED);
        Map<UUID, String> names = accountNames(rows);
        return rows.stream().map(s -> new TrackedRow(s, s.getAccountId() == null ? null : names.get(s.getAccountId()))).toList();
    }

    public record TrackedRow(RecurringSeries series, @Nullable String accountName) {
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public RecurringSeriesDto track(UUID userId, UUID id) {
        RecurringSeries s = owned(userId, id);
        requireStatus(s, RecurringStatus.PROPOSED);
        s.setStatus(RecurringStatus.TRACKED);
        s.setMissedCycles(0);
        return save(s);
    }

    /** "Not regular": never proposed again. */
    @Transactional
    public RecurringSeriesDto dismiss(UUID userId, UUID id) {
        RecurringSeries s = owned(userId, id);
        requireStatus(s, RecurringStatus.PROPOSED);
        s.setStatus(RecurringStatus.DISMISSED);
        return save(s);
    }

    /** Stops following a tracked series. */
    @Transactional
    public RecurringSeriesDto stop(UUID userId, UUID id) {
        RecurringSeries s = owned(userId, id);
        requireStatus(s, RecurringStatus.TRACKED);
        s.setStatus(RecurringStatus.ENDED);
        return save(s);
    }

    /**
     * The proposal became a loan: mark it. The loan module has no safe bulk way to attach past payments
     * (the EMI auto-linker pairs them), so the series' payments are left as they are.
     */
    @Transactional
    public RecurringSeriesDto converted(UUID userId, UUID id, UUID loanId) {
        RecurringSeries s = owned(userId, id);
        if (s.getStatus() != RecurringStatus.PROPOSED && s.getStatus() != RecurringStatus.TRACKED) {
            throw new ValidationException("This series cannot become a loan from status " + s.getStatus());
        }
        Loan loan = loanRepository.findById(loanId)
                .filter(l -> l.getUser() != null && userId.equals(l.getUser().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
        s.setStatus(RecurringStatus.LOAN);
        s.setLoanId(loan.getId());
        return save(s);
    }

    private RecurringSeries owned(UUID userId, UUID id) {
        return seriesRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("RecurringSeries", id));
    }

    private static void requireStatus(RecurringSeries s, RecurringStatus expected) {
        if (s.getStatus() != expected) {
            throw new ValidationException("Expected a " + expected + " series but it is " + s.getStatus());
        }
    }

    private RecurringSeriesDto save(RecurringSeries s) {
        s.setUpdatedAt(AppTime.nowInstant());
        seriesRepository.save(s);
        return toDtos(List.of(s)).get(0);
    }

    // ------------------------------------------------------------------ helpers

    private List<RecurringDetector.Payment> loadDebits(UUID userId, LocalDate from, LocalDate to, boolean notInASeries) {
        String jpql = DEBITS + (notInASeries ? NOT_IN_A_SERIES : "") + "ORDER BY t.date, t.id";
        List<Object[]> rows = entityManager.createQuery(jpql, Object[].class)
                .setParameter("userId", userId)
                .setParameter("debit", TransactionType.DEBIT)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("skip", SKIP_CATEGORIES)
                .getResultList();
        List<RecurringDetector.Payment> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new RecurringDetector.Payment((UUID) r[0], (LocalDate) r[1], ((BigDecimal) r[2]).abs(),
                    (UUID) r[5], (String) r[3], (String) r[4]));
        }
        return out;
    }

    private Map<UUID, String> accountNames(Collection<RecurringSeries> rows) {
        Set<UUID> ids = rows.stream().map(RecurringSeries::getAccountId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        for (Account a : accountRepository.findAllById(ids)) {
            out.put(a.getId(), a.getName());
        }
        return out;
    }

    private List<RecurringSeriesDto> toDtos(List<RecurringSeries> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = accountNames(rows);
        Map<UUID, Long> counts = new HashMap<>();
        Map<UUID, LocalDate> firsts = new HashMap<>();
        for (Object[] r : seriesRepository.paymentStats(rows.stream().map(RecurringSeries::getId).toList())) {
            counts.put((UUID) r[0], (Long) r[1]);
            firsts.put((UUID) r[0], (LocalDate) r[2]);
        }
        List<RecurringSeriesDto> out = new ArrayList<>(rows.size());
        for (RecurringSeries s : rows) {
            RecurringSeriesDto.LoanPrefillDto prefill = s.getKind() == RecurringKind.EMI
                    ? new RecurringSeriesDto.LoanPrefillDto(s.getDisplayName(), s.getAmount(),
                    s.getLastPaidOn().getDayOfMonth(), firsts.get(s.getId()), s.getAccountId())
                    : null;
            out.add(new RecurringSeriesDto(s.getId(), s.getKind(), s.getDisplayName(), s.getPeriod(), s.getAmount(),
                    s.getLastPaidOn(), s.getNextDueOn(), counts.getOrDefault(s.getId(), 0L).intValue(),
                    s.getAccountId() == null ? null : names.get(s.getAccountId()), s.getStatus(), prefill));
        }
        return out;
    }
}
