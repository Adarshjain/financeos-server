package com.financeos.domain.nps.link;

import com.financeos.domain.nps.NpsTier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure pairing of unlinked NPS contributions with NPS-looking payments; no database access. */
public final class NpsLinkMatcher {

    /** An unlinked own contribution. */
    public record Entry(UUID id, UUID accountId, NpsTier tier, BigDecimal amount, LocalDate postedDate) {
    }

    /** An NPS-looking DEBIT that nothing owns yet. */
    public record Payment(UUID id, UUID accountId, String accountName, LocalDate date, BigDecimal amount,
                          String description) {
    }

    public record Pair(Entry entry, Payment payment) {
    }

    /** What the user said no to: payments that are not NPS at all, and (entry, payment) pairs that are not each other. */
    public record Dismissals(Set<UUID> notNps, Set<String> pairs) {
        public static final Dismissals NONE = new Dismissals(Set.of(), Set.of());

        public static String pairKey(UUID entryId, UUID transactionId) {
            return entryId + "|" + transactionId;
        }

        boolean blocks(UUID entryId, UUID transactionId) {
            return notNps.contains(transactionId) || pairs.contains(pairKey(entryId, transactionId));
        }
    }

    /**
     * {@code unique}: one payment and one entry that fit only each other (safe to link). {@code ambiguous}: every
     * other fitting pair (the user decides). {@code unmatched}: payments that fit no entry and were not dismissed.
     */
    public record Plan(List<Pair> unique, List<Pair> ambiguous, List<Payment> unmatched) {
    }

    private NpsLinkMatcher() {
    }

    public static Plan plan(List<Entry> entries, List<Payment> payments, Dismissals dismissals) {
        Map<UUID, List<Pair>> byEntry = new HashMap<>();
        Map<UUID, List<Pair>> byPayment = new HashMap<>();
        List<Pair> all = new ArrayList<>();
        for (Entry e : entries) {
            for (Payment p : payments) {
                if (!dismissals.blocks(e.id(), p.id())
                        && NpsPaymentPatterns.fits(p.amount(), p.date(), e.amount(), e.postedDate())) {
                    Pair pair = new Pair(e, p);
                    all.add(pair);
                    byEntry.computeIfAbsent(e.id(), k -> new ArrayList<>()).add(pair);
                    byPayment.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(pair);
                }
            }
        }
        List<Pair> unique = new ArrayList<>();
        List<Pair> ambiguous = new ArrayList<>();
        for (Pair pair : all) {
            if (byEntry.get(pair.entry().id()).size() == 1 && byPayment.get(pair.payment().id()).size() == 1) {
                unique.add(pair);
            } else {
                ambiguous.add(pair);
            }
        }
        List<Payment> unmatched = payments.stream()
                .filter(p -> !byPayment.containsKey(p.id()) && !dismissals.notNps().contains(p.id()))
                .toList();
        return new Plan(unique, ambiguous, unmatched);
    }
}
