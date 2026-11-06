package com.financeos.domain.nps.link;

import com.financeos.domain.nps.NpsEntry;
import com.financeos.domain.nps.NpsEntryRepository;
import com.financeos.domain.nps.link.NpsLinkMatcher.Dismissals;
import com.financeos.domain.nps.link.NpsLinkMatcher.Entry;
import com.financeos.domain.nps.link.NpsLinkMatcher.Payment;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.autolink.AutoLinkCandidateLoader;
import com.financeos.domain.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Loads what the NPS payment linking needs for one user, in a few batched queries, and keeps the dismissals. */
@Component
public class NpsLinkStore {

    /** Cheap SQL prefilter (LOCATE, not LIKE: portable to the H2 tests); {@link NpsPaymentPatterns} decides. */
    private static final String PAYMENTS_FROM = "SELECT t.id, a.id, a.name, t.date, t.amount, t.description, t.sourcedDescription "
            + "FROM Transaction t JOIN t.account a WHERE t.user.id = :userId "
            + "AND t.type = com.financeos.domain.transaction.TransactionType.DEBIT AND t.amount IS NOT NULL "
            + "AND (LOCATE('NPS', UPPER(t.description)) > 0 "
            + "OR LOCATE('NPS', UPPER(t.sourcedDescription)) > 0 "
            + "OR LOCATE('PROTEAN', UPPER(t.description)) > 0 "
            + "OR LOCATE('PROTEAN', UPPER(t.sourcedDescription)) > 0 "
            + "OR LOCATE('PFRDA', UPPER(t.description)) > 0 "
            + "OR LOCATE('PFRDA', UPPER(t.sourcedDescription)) > 0 "
            + "OR LOCATE('NSDL', UPPER(t.description)) > 0 "
            + "OR LOCATE('NSDL', UPPER(t.sourcedDescription)) > 0 "
            + "OR LOCATE('NATIONAL PENSION', UPPER(t.description)) > 0 "
            + "OR LOCATE('NATIONAL PENSION', UPPER(t.sourcedDescription)) > 0) ";
    private static final String PAYMENTS = PAYMENTS_FROM + AutoLinkCandidateLoader.FREE + "ORDER BY t.date, t.id";
    /** The "Set up NPS?" scan stops at the cutoff in SQL, so it never reads the whole history. */
    private static final String SETUP_PAYMENTS = PAYMENTS_FROM + "AND t.date >= :from "
            + AutoLinkCandidateLoader.FREE + "ORDER BY t.date, t.id";

    @PersistenceContext
    private EntityManager entityManager;

    private final NpsEntryRepository entryRepository;
    private final NpsLinkDismissalRepository dismissalRepository;

    public NpsLinkStore(NpsEntryRepository entryRepository, NpsLinkDismissalRepository dismissalRepository) {
        this.entryRepository = entryRepository;
        this.dismissalRepository = dismissalRepository;
    }

    /** The user's unlinked own contributions. */
    public List<Entry> loadEntries(UUID userId) {
        List<Entry> out = new ArrayList<>();
        for (NpsEntry e : entryRepository.findUnlinkedSelfContributions(userId)) {
            out.add(new Entry(e.getId(), e.getAccount().getId(), e.getTier(), e.getAmount(), e.getPostedDate()));
        }
        return out;
    }

    /** The user's NPS-looking DEBITs that nothing owns yet. */
    public List<Payment> loadPayments(UUID userId) {
        return payments(entityManager.createQuery(PAYMENTS, Object[].class).setParameter("userId", userId));
    }

    private static List<Payment> payments(jakarta.persistence.TypedQuery<Object[]> query) {
        List<Object[]> rows = query.getResultList();
        List<Payment> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (NpsPaymentPatterns.looksLikeNps((String) r[5], (String) r[6])) {
                String text = r[5] != null && !((String) r[5]).isBlank() ? (String) r[5] : (String) r[6];
                out.add(new Payment((UUID) r[0], (UUID) r[1], (String) r[2], (LocalDate) r[3], (BigDecimal) r[4], text));
            }
        }
        return out;
    }

    /** How far back an NPS-looking payment counts for the "Set up NPS?" suggestion. */
    public static final int SETUP_YEARS = 10;

    /**
     * The NPS-looking debits of the last {@value #SETUP_YEARS} years that are not marked "not NPS": what the "Set up
     * NPS?" suggestion counts. The cutoff is in the SQL; two batched queries, whatever the number of payments.
     */
    public List<Payment> loadSetupPayments(UUID userId, LocalDate today) {
        LocalDate from = today.minusYears(SETUP_YEARS);
        List<Payment> payments = payments(entityManager.createQuery(SETUP_PAYMENTS, Object[].class)
                .setParameter("userId", userId).setParameter("from", from));
        if (payments.isEmpty()) {
            return payments;
        }
        Set<UUID> notNps = loadDismissals(userId).notNps();
        return payments.stream().filter(p -> !notNps.contains(p.id())).toList();
    }

    /** "Not NPS" on all of these payments at once: one query for what is already marked, one batch insert. */
    public void dismissNotNps(UUID userId, List<UUID> transactionIds) {
        Set<UUID> already = loadDismissals(userId).notNps();
        List<NpsLinkDismissal> fresh = new ArrayList<>();
        for (UUID id : transactionIds) {
            if (already.contains(id)) {
                continue;
            }
            NpsLinkDismissal d = new NpsLinkDismissal();
            d.setUser(entityManager.getReference(User.class, userId));
            d.setTransaction(entityManager.getReference(Transaction.class, id));
            fresh.add(d);
        }
        dismissalRepository.saveAllAndFlush(fresh);
    }

    public Dismissals loadDismissals(UUID userId) {
        Set<UUID> notNps = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (Object[] r : dismissalRepository.findPairsByUserId(userId)) {
            UUID txn = (UUID) r[0];
            if (r[1] == null) {
                notNps.add(txn);
            } else {
                pairs.add(Dismissals.pairKey((UUID) r[1], txn));
            }
        }
        return new Dismissals(notNps, pairs);
    }

    /** Remembers a dismissal once: entry-level when {@code entryId} is given, otherwise "this payment is not NPS". */
    public void dismiss(UUID userId, UUID transactionId, UUID entryId) {
        long existing = entryId == null ? dismissalRepository.countNotNps(userId, transactionId)
                : dismissalRepository.countPair(userId, transactionId, entryId);
        if (existing > 0) {
            return;
        }
        NpsLinkDismissal d = new NpsLinkDismissal();
        d.setUser(entityManager.getReference(User.class, userId));
        d.setTransaction(entityManager.getReference(Transaction.class, transactionId));
        if (entryId != null) {
            d.setEntry(entityManager.getReference(NpsEntry.class, entryId));
        }
        dismissalRepository.saveAndFlush(d);
    }
}
