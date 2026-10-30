package com.financeos.domain.transaction.autolink;

import com.financeos.domain.transaction.autolink.AutoLinkOutcome.Counts;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the planned pairs of one linker the way {@link TransferLinker} does: every pair in its own
 * REQUIRES_NEW transaction, after a re-check that its transaction is still free, with its own try/catch so one
 * bad pair neither rolls back the others nor stops the pass. A pair that fails makes the outcome partial.
 */
final class PairWriter {

    private static final Logger log = LoggerFactory.getLogger(PairWriter.class);

    /** One planned link: the transaction it claims, a label for the log, and the existing confirm path that writes it. */
    record Planned(UUID transactionId, String label, BooleanSupplier stillOpen, Runnable write) {
        Planned(UUID transactionId, String label, Runnable write) {
            this(transactionId, label, () -> true, write);
        }
    }

    private final AutoLinkCandidateLoader loader;
    private final TransactionTemplate isolated;

    PairWriter(AutoLinkCandidateLoader loader, PlatformTransactionManager transactionManager) {
        this.loader = loader;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    AutoLinkOutcome write(UUID userId, SuggestionKind kind, String linkerName, List<Planned> planned) {
        int linked = 0;
        int failed = 0;
        for (Planned p : planned) {
            try {
                Boolean done = isolated.execute(status -> {
                    // Never overwrite a link: the obligation (lending row, dividend) must still be open too.
                    if (!p.stillOpen().getAsBoolean() || loader.freeIds(userId, List.of(p.transactionId())).isEmpty()) {
                        return false;
                    }
                    p.write().run();
                    return true;
                });
                if (Boolean.TRUE.equals(done)) {
                    linked++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("{} auto-link of {} failed for user {}: {}", linkerName, p.label(), userId, e.getMessage());
            }
        }
        log.info("{} finished for user {}: linked={}, failed={}", linkerName, userId, linked, failed);
        Map<SuggestionKind, Counts> counts = new EnumMap<>(SuggestionKind.class);
        counts.put(kind, new Counts(linked, 0));
        return AutoLinkOutcome.of(counts, failed > 0);
    }
}
