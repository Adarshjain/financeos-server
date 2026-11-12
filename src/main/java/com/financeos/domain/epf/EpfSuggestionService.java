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

    /** Why a suggested pairing was not linked by itself (EPF never links without the user). */
    public static final String WHY_MULTIPLE_CREDITS = "MULTIPLE_CREDITS";
    public static final String WHY_SHARED_CREDIT = "SHARED_CREDIT";
    public static final String WHY_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION";

    /**
     * A withdrawal and the bank credit suggested for it. {@code why}: {@value #WHY_MULTIPLE_CREDITS} (more than one
     * credit fits this withdrawal), {@value #WHY_SHARED_CREDIT} (this credit also fits another unlinked withdrawal),
     * otherwise {@value #WHY_NEEDS_CONFIRMATION} (a single fit; EPF links always wait for the user).
     */
    public record Suggestion(EpfEntry entry, Transaction credit, String why) {
    }

    private record Loaded(List<EpfEntry> wanting, List<Transaction> credits, Set<UUID> linked,
                          Map<UUID, Set<UUID>> dismissed) {
    }

    /** Open suggestions for all of the user's unlinked withdrawals, newest withdrawal first. */
    public List<Suggestion> pending(UUID userId) {
        List<EpfEntry> entries = entryRepository.findAllForUser(userId);
        Loaded loaded = load(userId, entries);
        if (loaded == null) {
            return List.of();
        }
        Map<UUID, Transaction> matches = EpfSuggestionMatcher.match(loaded.wanting(), loaded.credits(), loaded.linked(),
                loaded.dismissed());
        List<Suggestion> out = new java.util.ArrayList<>();
        for (EpfEntry entry : loaded.wanting().stream()
                .sorted(java.util.Comparator.comparing(EpfEntry::getCreditedOn).reversed()).toList()) {
            Transaction credit = matches.get(entry.getId());
            if (credit != null) {
                out.add(new Suggestion(entry, credit, why(loaded, entry, credit)));
            }
        }
        return out;
    }

    private static String why(Loaded loaded, EpfEntry entry, Transaction credit) {
        Set<UUID> ruledOut = loaded.dismissed().getOrDefault(entry.getId(), Set.of());
        long creditsForEntry = loaded.credits().stream()
                .filter(c -> !loaded.linked().contains(c.getId()) && !ruledOut.contains(c.getId())
                        && EpfSuggestionMatcher.fits(entry, c))
                .count();
        if (creditsForEntry > 1) {
            return WHY_MULTIPLE_CREDITS;
        }
        boolean shared = loaded.wanting().stream().anyMatch(other -> !other.getId().equals(entry.getId())
                && !loaded.dismissed().getOrDefault(other.getId(), Set.of()).contains(credit.getId())
                && EpfSuggestionMatcher.fits(other, credit));
        return shared ? WHY_SHARED_CREDIT : WHY_NEEDS_CONFIRMATION;
    }

    /** Suggested bank credit per withdrawal id among {@code targets} (all of the user's entries are {@code allEntries}). */
    public Map<UUID, Transaction> suggest(UUID userId, Collection<EpfEntry> allEntries, Collection<EpfEntry> targets) {
        Set<UUID> targetIds = new HashSet<>();
        targets.stream().filter(EpfSuggestionService::wantsSuggestion).forEach(e -> targetIds.add(e.getId()));
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        Loaded loaded = load(userId, allEntries);
        if (loaded == null) {
            return Map.of();
        }
        Map<UUID, Transaction> all = EpfSuggestionMatcher.match(loaded.wanting(), loaded.credits(), loaded.linked(),
                loaded.dismissed());
        all.keySet().retainAll(targetIds);
        return all;
    }

    /**
     * One credits query and one dismissals query. Credits are shared out across every unlinked withdrawal, so a page
     * of them agrees with the full list. Null when there is nothing to match.
     */
    private Loaded load(UUID userId, Collection<EpfEntry> allEntries) {
        List<EpfEntry> wanting = allEntries.stream().filter(EpfSuggestionService::wantsSuggestion).toList();
        if (wanting.isEmpty()) {
            return null;
        }
        LocalDate from = wanting.stream().map(EpfEntry::getCreditedOn).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = wanting.stream().map(EpfEntry::getCreditedOn).max(LocalDate::compareTo).orElseThrow()
                .plusDays(EpfSuggestionMatcher.WINDOW_DAYS);
        List<Transaction> credits = entryRepository.findCreditsBetween(userId, from, to);
        if (credits.isEmpty()) {
            return null;
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
        return new Loaded(wanting, credits, linked, dismissed);
    }
}
