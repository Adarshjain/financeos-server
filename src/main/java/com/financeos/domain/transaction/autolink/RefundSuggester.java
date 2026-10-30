package com.financeos.domain.transaction.autolink;

import com.financeos.domain.categorization.DescriptionNormalizer;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkOutcome.Counts;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Suggests, never links: a CREDIT on an account that follows a DEBIT of the same merchant on the same
 * account within {@value #WINDOW_DAYS} days, for no more than the debit's amount, may be its refund. The
 * merchant is the {@link DescriptionNormalizer} key of the bank text, the same cleaning categorisation rules
 * rely on. The suggestion is keyed on the credit; its candidates are the debits, exact amount first, then
 * the nearest in date.
 */
@Component
@Order(20)
public class RefundSuggester implements AutoLinker {

    private static final Logger log = LoggerFactory.getLogger(RefundSuggester.class);

    static final int WINDOW_DAYS = 60;

    private final AutoLinkCandidateLoader loader;
    private final AutoLinkStore store;

    public RefundSuggester(AutoLinkCandidateLoader loader, AutoLinkStore store) {
        this.loader = loader;
        this.store = store;
    }

    @Override
    public Set<SuggestionKind> ownedKinds() {
        return Set.of(SuggestionKind.REFUND);
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        Map<UUID, List<UUID>> suggestions = plan(loader.load(userId), store.loadRejectionKeys(userId));
        store.syncSuggestions(userId, ownedKinds(), Map.of(SuggestionKind.REFUND, suggestions));
        log.info("Refund suggester finished for user {}: suggested={}", userId, suggestions.size());
        Map<SuggestionKind, Counts> counts = new EnumMap<>(SuggestionKind.class);
        counts.put(SuggestionKind.REFUND, new Counts(0, suggestions.size()));
        return AutoLinkOutcome.of(counts);
    }

    /** Credit id to its candidate debit ids, best first. */
    static Map<UUID, List<UUID>> plan(List<AutoLinkCandidate> candidates, Set<String> rejections) {
        Map<String, List<AutoLinkCandidate>> debitsByAccountAndMerchant = new HashMap<>();
        for (AutoLinkCandidate c : candidates) {
            if (c.type() == TransactionType.DEBIT) {
                String key = key(c);
                if (key != null) {
                    debitsByAccountAndMerchant.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                }
            }
        }
        Map<UUID, List<UUID>> out = new LinkedHashMap<>();
        for (AutoLinkCandidate credit : candidates) {
            if (credit.type() != TransactionType.CREDIT) {
                continue;
            }
            String key = key(credit);
            List<AutoLinkCandidate> debits = key == null ? null : debitsByAccountAndMerchant.get(key);
            if (debits == null) {
                continue;
            }
            List<UUID> ranked = debits.stream()
                    .filter(d -> {
                        long days = ChronoUnit.DAYS.between(d.date(), credit.date());
                        return days >= 0 && days <= WINDOW_DAYS;
                    })
                    .filter(d -> credit.amount().compareTo(d.amount()) <= 0)
                    .filter(d -> !AutoLinkStore.isRejected(rejections, SuggestionKind.REFUND, credit.id(), d.id()))
                    .sorted(Comparator.comparing((AutoLinkCandidate d) -> d.amount().compareTo(credit.amount()) == 0 ? 0 : 1)
                            .thenComparing(d -> ChronoUnit.DAYS.between(d.date(), credit.date()))
                            .thenComparing(AutoLinkCandidate::id))
                    .map(AutoLinkCandidate::id)
                    .toList();
            if (!ranked.isEmpty()) {
                out.put(credit.id(), ranked);
            }
        }
        return out;
    }

    private static final Set<String> MARKERS = Set.of("REFUND", "REV", "REVERSAL", "RFND");

    private static String key(AutoLinkCandidate c) {
        String merchant = merchantKey(c.merchant());
        return merchant.isEmpty() ? null : c.accountId() + "|" + merchant;
    }

    /**
     * The normalised merchant with leading and trailing refund markers (REFUND, REV, REVERSAL, RFND and a
     * leading CREDIT FOR) removed, so "UPI REFUND SWIGGY 4821937" and "SWIGGY" compare equal.
     */
    static String merchantKey(String description) {
        List<String> tokens = new ArrayList<>(Arrays.asList(DescriptionNormalizer.normalize(description).split(" ")));
        tokens.removeIf(String::isEmpty);
        boolean changed = true;
        while (changed && !tokens.isEmpty()) {
            changed = false;
            if (tokens.size() >= 2 && tokens.get(0).equals("CREDIT") && tokens.get(1).equals("FOR")) {
                tokens.subList(0, 2).clear();
                changed = true;
            } else if (MARKERS.contains(tokens.get(0))) {
                tokens.remove(0);
                changed = true;
            } else if (MARKERS.contains(tokens.get(tokens.size() - 1))) {
                tokens.remove(tokens.size() - 1);
                changed = true;
            }
        }
        return String.join(" ", tokens);
    }
}
