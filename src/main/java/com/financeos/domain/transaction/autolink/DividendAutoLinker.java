package com.financeos.domain.transaction.autolink;

import com.financeos.domain.investment.dividend.DividendReceiptService;
import com.financeos.domain.investment.dividend.DividendReceiptService.UniqueMatch;
import com.financeos.domain.transaction.autolink.PairWriter.Planned;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Links a dividend row to its bank credit when the reconciliation scoring gives that pair as the only fit both
 * ways at the EXACT or NET_OF_TDS tier ({@link DividendReceiptService#findUniqueMatches}); a fuzzy tier is
 * never auto-linked. Written through the same call Confirm uses (with the TDS write-back,
 * {@link DividendReceiptService#autoLinkTransaction}); the link's origin becomes AUTO.
 */
@Component
@Order(40)
public class DividendAutoLinker implements AutoLinker {

    private final AutoLinkStore store;
    private final DividendReceiptService receipts;
    private final PairWriter writer;

    public DividendAutoLinker(AutoLinkCandidateLoader loader, AutoLinkStore store, DividendReceiptService receipts,
                              PlatformTransactionManager transactionManager) {
        this.store = store;
        this.receipts = receipts;
        this.writer = new PairWriter(loader, transactionManager);
    }

    @Override
    public Set<SuggestionKind> ownedKinds() {
        return Set.of();
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        Set<String> rejections = store.loadRejectionKeys(userId);
        List<UniqueMatch> matches = receipts.findUniqueMatches(
                (dividendId, txId) -> AutoLinkStore.isRejected(rejections, SuggestionKind.DIVIDEND, txId, dividendId));
        if (matches.isEmpty()) {
            return AutoLinkOutcome.of(Map.of());
        }
        List<Planned> planned = matches.stream()
                .map(m -> new Planned(m.transactionId(), "dividend " + m.dividendId(),
                        () -> receipts.isUnlinked(m.dividendId()),
                        () -> receipts.autoLinkTransaction(m.dividendId(), m.transactionId())))
                .toList();
        return writer.write(userId, SuggestionKind.DIVIDEND, "Dividend linker", planned);
    }
}
