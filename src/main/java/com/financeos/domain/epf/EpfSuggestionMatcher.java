package com.financeos.domain.epf;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure matching of an unlinked withdrawal to the bank credit it likely landed as: a credit of exactly the
 * received amount, dated from the credit date of the withdrawal up to {@value #WINDOW_DAYS} days later.
 */
public final class EpfSuggestionMatcher {

    public static final int WINDOW_DAYS = 15;

    private EpfSuggestionMatcher() {
    }

    /** True when {@code credit} is a CREDIT of exactly the withdrawal's received amount inside the date window. */
    public static boolean fits(EpfEntry withdrawal, Transaction credit) {
        BigDecimal received = EpfLedger.received(withdrawal);
        if (received.signum() <= 0 || credit.getType() != TransactionType.CREDIT || credit.getAmount() == null
                || credit.getAmount().compareTo(received) != 0) {
            return false;
        }
        return inWindow(withdrawal, credit);
    }

    public static boolean inWindow(EpfEntry withdrawal, Transaction credit) {
        long days = ChronoUnit.DAYS.between(withdrawal.getCreditedOn(), credit.getDate());
        return days >= 0 && days <= WINDOW_DAYS;
    }

    /**
     * The suggestion per withdrawal id (withdrawals without one are absent). {@code linkedTransactionIds}
     * are credits already linked to any EPF entry; {@code dismissed} maps an entry id to the credits ruled
     * out for it. Every credit goes to at most one withdrawal: fitting pairs are taken closest date first
     * (ties: the earlier withdrawal, then the earlier id), so same-amount withdrawals never share a credit.
     */
    public static Map<UUID, Transaction> match(Collection<EpfEntry> withdrawals, Collection<Transaction> credits,
                                               Set<UUID> linkedTransactionIds, Map<UUID, Set<UUID>> dismissed) {
        record Pair(EpfEntry withdrawal, Transaction credit, long distance) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (EpfEntry withdrawal : withdrawals) {
            Set<UUID> ruledOut = dismissed.getOrDefault(withdrawal.getId(), Set.of());
            for (Transaction credit : credits) {
                if (!linkedTransactionIds.contains(credit.getId()) && !ruledOut.contains(credit.getId())
                        && fits(withdrawal, credit)) {
                    pairs.add(new Pair(withdrawal, credit, ChronoUnit.DAYS.between(withdrawal.getCreditedOn(), credit.getDate())));
                }
            }
        }
        pairs.sort(Comparator.comparingLong(Pair::distance)
                .thenComparing(p -> p.withdrawal().getCreditedOn())
                .thenComparing(p -> p.withdrawal().getId())
                .thenComparing(p -> p.credit().getId()));
        Map<UUID, Transaction> out = new HashMap<>();
        Set<UUID> usedCredits = new HashSet<>();
        for (Pair pair : pairs) {
            if (!out.containsKey(pair.withdrawal().getId()) && usedCredits.add(pair.credit().getId())) {
                out.put(pair.withdrawal().getId(), pair.credit());
            }
        }
        return out;
    }
}
