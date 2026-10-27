package com.financeos.domain.epf;

import com.financeos.domain.transaction.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Loads what {@link EpfSuggestionMatcher} needs with one credits query and one dismissals query per call. */
@Service
@Transactional(readOnly = true)
public class EpfSuggestionService {

    private final EpfEntryRepository entryRepository;
    private final EpfLinkDismissalRepository dismissalRepository;

    public EpfSuggestionService(EpfEntryRepository entryRepository, EpfLinkDismissalRepository dismissalRepository) {
        this.entryRepository = entryRepository;
        this.dismissalRepository = dismissalRepository;
    }

    /** Withdrawals that are eligible for a suggestion: unlinked, with something received. */
    public static boolean wantsSuggestion(EpfEntry entry) {
        return entry.getType() == EpfEntryType.WITHDRAWAL && entry.getTransaction() == null
                && EpfLedger.received(entry).signum() > 0;
    }

    /**
     * Suggested bank credit per withdrawal id among {@code targets}. {@code allEntries} (all the user's
     * entries) tell which credits are already linked.
     */
    public Map<UUID, Transaction> suggest(UUID userId, Collection<EpfEntry> allEntries, Collection<EpfEntry> targets) {
        Set<UUID> targetIds = new HashSet<>();
        targets.stream().filter(EpfSuggestionService::wantsSuggestion).forEach(e -> targetIds.add(e.getId()));
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        // Credits are shared out across every unlinked withdrawal, so a page of them agrees with the full list.
        List<EpfEntry> wanting = allEntries.stream().filter(EpfSuggestionService::wantsSuggestion).toList();
        if (wanting.isEmpty()) {
            return Map.of();
        }
        LocalDate from = wanting.stream().map(EpfEntry::getCreditedOn).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = wanting.stream().map(EpfEntry::getCreditedOn).max(LocalDate::compareTo).orElseThrow()
                .plusDays(EpfSuggestionMatcher.WINDOW_DAYS);
        List<Transaction> credits = entryRepository.findCreditsBetween(userId, from, to);
        if (credits.isEmpty()) {
            return Map.of();
        }
        Set<UUID> linked = new HashSet<>();
        for (EpfEntry entry : allEntries) {
            if (entry.getTransaction() != null) {
                linked.add(entry.getTransaction().getId());
            }
        }
        Map<UUID, Set<UUID>> dismissed = new HashMap<>();
        for (Object[] pair : dismissalRepository.findPairsForUser(userId)) {
            dismissed.computeIfAbsent((UUID) pair[0], k -> new HashSet<>()).add((UUID) pair[1]);
        }
        Map<UUID, Transaction> all = EpfSuggestionMatcher.match(wanting, credits, linked, dismissed);
        all.keySet().retainAll(targetIds);
        return all;
    }
}
