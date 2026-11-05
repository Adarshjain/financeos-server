package com.financeos.domain.transaction.autolink;

import com.financeos.domain.nps.link.NpsLinkMatcher;
import com.financeos.domain.nps.link.NpsLinkMatcher.Entry;
import com.financeos.domain.nps.link.NpsLinkMatcher.Pair;
import com.financeos.domain.nps.link.NpsLinkMatcher.Payment;
import com.financeos.domain.nps.link.NpsLinkService;
import com.financeos.domain.nps.link.NpsLinkStore;
import com.financeos.domain.transaction.autolink.PairWriter.Planned;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Links an NPS contribution to its bank or card payment when that is the only fit both ways (one payment, one
 * contribution; see {@link NpsLinkMatcher}). Anything ambiguous is left to the Inbox. Entries, payments and
 * dismissals are each read once for the whole user.
 */
@Component
@Order(60)
public class NpsAutoLinker implements AutoLinker {

    private final NpsLinkStore store;
    private final NpsLinkService linkService;
    private final PairWriter writer;

    public NpsAutoLinker(AutoLinkCandidateLoader loader, NpsLinkStore store, NpsLinkService linkService,
                         PlatformTransactionManager transactionManager) {
        this.store = store;
        this.linkService = linkService;
        this.writer = new PairWriter(loader, transactionManager);
    }

    @Override
    public Set<SuggestionKind> ownedKinds() {
        return Set.of();
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        List<Entry> entries = store.loadEntries(userId);
        if (entries.isEmpty()) {
            return AutoLinkOutcome.of(Map.of());
        }
        List<Payment> payments = store.loadPayments(userId);
        if (payments.isEmpty()) {
            return AutoLinkOutcome.of(Map.of());
        }
        List<Pair> pairs = NpsLinkMatcher.plan(entries, payments, store.loadDismissals(userId)).unique();
        List<Planned> planned = pairs.stream()
                .map(p -> new Planned(p.payment().id(), "NPS entry " + p.entry().id(),
                        () -> linkService.isOpen(userId, p.entry().id()),
                        () -> linkService.autoLink(userId, p.entry().id(), p.payment().id())))
                .toList();
        return writer.write(userId, SuggestionKind.NPS, "NPS linker", planned);
    }
}
