package com.financeos.domain.transaction.autolink;

import com.financeos.domain.lending.LendingService;
import com.financeos.domain.lending.LendingService.UnlinkedEntry;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.obligation.MatchRules;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.PairWriter.Planned;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Links a lending entry that has no transaction to the one transaction that fits it both ways: the match
 * suggestions view's rule ({@link MatchRules#lendingEntryFits}: direction decides DEBIT or CREDIT, the date
 * within 7 days) but with the amount EXACT (the view allows Rs 20). Written through the same call Confirm uses
 * ({@link LendingService#autoLinkTransaction}); the entry's origin becomes AUTO.
 */
@Component
@Order(30)
public class LendingAutoLinker implements AutoLinker {

    /** Entry-to-transaction match window (days), per {@link MatchRules#lendingEntryFits}. */
    static final int WINDOW_DAYS = 7;

    private final AutoLinkCandidateLoader loader;
    private final AutoLinkStore store;
    private final LendingService lendingService;
    private final PairWriter writer;

    public LendingAutoLinker(AutoLinkCandidateLoader loader, AutoLinkStore store, LendingService lendingService,
                             PlatformTransactionManager transactionManager) {
        this.loader = loader;
        this.store = store;
        this.lendingService = lendingService;
        this.writer = new PairWriter(loader, transactionManager);
    }

    @Override
    public Set<SuggestionKind> ownedKinds() {
        return Set.of(SuggestionKind.LENDING);
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        return link(userId, LinkScope.ALL);
    }

    @Override
    public AutoLinkOutcome link(UUID userId, LinkScope scope) {
        List<UnlinkedEntry> entries = lendingService.getUnlinkedEntries();
        AutoLinkOutcome linked = AutoLinkOutcome.of(Map.of());
        // Entries within the 7-day window of the span can change; the transactions they fit are read 3 windows out.
        LinkScope anchors = scope.anchors(WINDOW_DAYS);
        if (entries.stream().anyMatch(e -> anchors.contains(e.entryDate()))) {
            linked = linkEntries(userId, entries, scope);
        }
        // The repayment suggestions come from LendingService over all history, so that kind is always synced in full.
        Map<UUID, List<UUID>> repayments = repaymentSuggestions(store.loadRejectionKeys(userId),
                lendingService.getRepaymentSuggestions());
        store.syncSuggestions(userId, ownedKinds(), Map.of(SuggestionKind.LENDING, repayments));
        if (repayments.isEmpty()) {
            return linked;
        }
        return AutoLinkOutcome.of(AutoLinkOutcome.merge(linked.byKind(),
                Map.of(SuggestionKind.LENDING, new AutoLinkOutcome.Counts(0, repayments.size()))), linked.partial());
    }

    private AutoLinkOutcome linkEntries(UUID userId, List<UnlinkedEntry> entries, LinkScope scope) {
        List<AutoLinkCandidate> candidates = scope.isAll() ? loader.load(userId) : loader.load(userId, scope.candidates(WINDOW_DAYS));
        List<Pair> pairs = plan(entries, candidates, store.loadRejectionKeys(userId), scope.anchors(WINDOW_DAYS));
        List<Planned> planned = pairs.stream()
                .map(p -> new Planned(p.transactionId(), "lending " + p.entry().id(),
                        () -> lendingService.isUnlinked(p.entry().id()),
                        () -> lendingService.autoLinkTransaction(p.entry().id(), p.transactionId())))
                .toList();
        return writer.write(userId, SuggestionKind.LENDING, "Lending linker", planned);
    }

    // ---------------------------------------------------------------- pure planning

    /**
     * Credit id to its lending candidate: a credit that names a person who owes it but has no entry cannot be
     * linked on its own, so it is suggested. A pairing remembered (rejected) against the person is left out;
     * the rejection's other side is the person's id so a newer entry does not bring it back.
     */
    static Map<UUID, List<UUID>> repaymentSuggestions(Set<String> rejections,
                                                      List<LendingService.RepaymentSuggestion> found) {
        Map<UUID, List<UUID>> out = new LinkedHashMap<>();
        for (LendingService.RepaymentSuggestion s : found) {
            if (!AutoLinkStore.isRejected(rejections, SuggestionKind.LENDING, s.transactionId(), s.counterpartyId())) {
                out.computeIfAbsent(s.transactionId(), k -> new ArrayList<>()).add(s.lendingId());
            }
        }
        return out;
    }

    record Pair(UnlinkedEntry entry, UUID transactionId) {
    }

    static List<Pair> plan(List<UnlinkedEntry> entries, List<AutoLinkCandidate> candidates, Set<String> rejections) {
        return plan(entries, candidates, rejections, LinkScope.ALL);
    }

    /** As above, but only entries dated within {@code anchors} are linked; the others still count for the one-entry-per-transaction check. */
    static List<Pair> plan(List<UnlinkedEntry> entries, List<AutoLinkCandidate> candidates, Set<String> rejections,
                           LinkScope anchors) {
        Map<TransactionType, List<AutoLinkCandidate>> byType = new HashMap<>();
        for (AutoLinkCandidate c : candidates) {
            byType.computeIfAbsent(c.type(), k -> new ArrayList<>()).add(c);
        }

        Map<UnlinkedEntry, List<UUID>> fitsOfEntry = new LinkedHashMap<>();
        Map<UUID, Integer> entriesOfTxn = new HashMap<>();
        for (UnlinkedEntry entry : entries) {
            List<UUID> fits = new ArrayList<>();
            for (AutoLinkCandidate c : byType.getOrDefault(TransactionReferenceValidator.expectedTypeFor(entry.direction()), List.of())) {
                if (MatchRules.lendingEntryFits(entry.amount(), entry.entryDate(), c.amount(), c.date(), BigDecimal.ZERO)
                        && !AutoLinkStore.isRejected(rejections, SuggestionKind.LENDING, c.id(), entry.id())) {
                    fits.add(c.id());
                    entriesOfTxn.merge(c.id(), 1, Integer::sum);
                }
            }
            fitsOfEntry.put(entry, fits);
        }

        List<Pair> out = new ArrayList<>();
        for (Map.Entry<UnlinkedEntry, List<UUID>> e : fitsOfEntry.entrySet()) {
            if (anchors.contains(e.getKey().entryDate()) && e.getValue().size() == 1 && entriesOfTxn.get(e.getValue().get(0)) == 1) {
                out.add(new Pair(e.getKey(), e.getValue().get(0)));
            }
        }
        return out;
    }
}
