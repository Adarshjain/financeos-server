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
        return Set.of();
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        List<UnlinkedEntry> entries = lendingService.getUnlinkedEntries();
        if (entries.isEmpty()) {
            return AutoLinkOutcome.of(Map.of());
        }
        List<Pair> pairs = plan(entries, loader.load(userId), store.loadRejectionKeys(userId));
        List<Planned> planned = pairs.stream()
                .map(p -> new Planned(p.transactionId(), "lending " + p.entry().id(),
                        () -> lendingService.isUnlinked(p.entry().id()),
                        () -> lendingService.autoLinkTransaction(p.entry().id(), p.transactionId())))
                .toList();
        return writer.write(userId, SuggestionKind.LENDING, "Lending linker", planned);
    }

    // ---------------------------------------------------------------- pure planning

    record Pair(UnlinkedEntry entry, UUID transactionId) {
    }

    static List<Pair> plan(List<UnlinkedEntry> entries, List<AutoLinkCandidate> candidates, Set<String> rejections) {
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
            if (e.getValue().size() == 1 && entriesOfTxn.get(e.getValue().get(0)) == 1) {
                out.add(new Pair(e.getKey(), e.getValue().get(0)));
            }
        }
        return out;
    }
}
