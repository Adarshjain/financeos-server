package com.financeos.domain.statement.file;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the original file of a statement: stores it and points the statement at it. Both methods never throw and
 * must be called with no transaction open (the store uses its own connection).
 */
@Service
public class StatementFileAttacher {

    private static final Logger log = LoggerFactory.getLogger(StatementFileAttacher.class);

    private final StatementFileStore store;
    private final StatementRepository statementRepository;
    private final TransactionTemplate tx;

    public StatementFileAttacher(StatementFileStore store, StatementRepository statementRepository,
                                 PlatformTransactionManager txManager) {
        this.store = store;
        this.statementRepository = statementRepository;
        this.tx = new TransactionTemplate(txManager);
    }

    /** What {@link #attachDetailed} did. NOT_STORED_PERMANENT will never succeed for these bytes; TRANSIENT may later. */
    public enum AttachResult { ATTACHED, ALREADY_HAD_FILE, STATEMENT_MISSING, NOT_STORED_PERMANENT, NOT_STORED_TRANSIENT }

    /** Stores the file and sets the statement's file when it has none. A missing statement is a no-op. */
    public void attach(UUID userId, UUID statementId, byte[] bytes, String originalName) {
        try {
            Optional<UUID> fileId = store.store(userId, bytes, originalName);
            if (fileId.isEmpty()) {
                return;
            }
            setIfMissing(statementId, fileId.get());
        } catch (Exception e) {
            log.warn("STATEMENT_FILE_ATTACH_FAILED {}", e.toString());
        }
    }

    /** Same as {@link #attach} but says what happened. Never throws; no transaction may be open. */
    public AttachResult attachDetailed(UUID userId, UUID statementId, byte[] bytes, String originalName) {
        try {
            StatementFileStore.StoreResult stored = store.storeDetailed(userId, bytes, originalName);
            if (stored.id() == null) {
                return switch (stored.outcome()) {
                    case TOO_LARGE, UNSUPPORTED, EMPTY -> AttachResult.NOT_STORED_PERMANENT;
                    default -> AttachResult.NOT_STORED_TRANSIENT;
                };
            }
            return setIfMissing(statementId, stored.id());
        } catch (Exception e) {
            log.warn("STATEMENT_FILE_ATTACH_FAILED {}", e.toString());
            return AttachResult.NOT_STORED_TRANSIENT;
        }
    }

    /**
     * An identical re-upload back-fills the file of the account's stored statement with this sha that has none.
     * Nothing is stored when there is no such statement.
     */
    public void attachIfStoredStatementLacksFile(UUID userId, UUID accountId, String sha256, byte[] bytes, String originalName) {
        try {
            List<UUID> lacking = statementRepository.findIdsByAccountIdAndFileSha256WithoutFile(accountId, sha256);
            if (lacking.isEmpty()) {
                return;
            }
            Optional<UUID> fileId = store.store(userId, bytes, originalName);
            if (fileId.isEmpty()) {
                return;
            }
            for (UUID statementId : lacking) {
                setIfMissing(statementId, fileId.get());
            }
        } catch (Exception e) {
            log.warn("STATEMENT_FILE_ATTACH_FAILED {}", e.toString());
        }
    }

    /**
     * The id of the account's live statement with exactly this draft's period and no file whose stored figures prove
     * the draft is the same statement. Call it before anything changes the stored statement. Never throws.
     */
    public Optional<UUID> provenCopyTarget(UUID accountId, StatementDraft draft) {
        try {
            if (draft == null || draft.periodStart() == null || draft.periodEnd() == null) {
                return Optional.empty();
            }
            Optional<Statement> found = statementRepository.findLiveWithoutFileByAccountIdAndPeriod(
                    accountId, draft.periodStart(), draft.periodEnd());
            if (found.isEmpty()) {
                return Optional.empty();
            }
            FigureProof proof = proveSameFigures(found.get(), draft);
            if (!proof.holds()) {
                log.info("STATEMENT_FILE_COPY_NOT_PROVEN statement={} compared={} differing={}",
                        found.get().getId(), proof.compared(), proof.differing());
                return Optional.empty();
            }
            return Optional.of(found.get().getId());
        } catch (Exception e) {
            log.warn("STATEMENT_FILE_ATTACH_FAILED {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * A different-bytes copy of the same statement becomes the stored file of the account's statement with exactly this
     * period that has none, and that statement's file hash becomes the copy's. The caller proves it is the same
     * statement and passes its id; any other statement is left alone. Never throws; no transaction may be open.
     */
    public boolean attachCopyOfSameStatement(UUID userId, UUID accountId, String sha256, StatementDraft draft, byte[] bytes,
                                             String originalName, UUID sameStatementId) {
        try {
            if (sameStatementId == null || draft == null || draft.periodStart() == null || draft.periodEnd() == null) {
                return false;
            }
            Optional<Statement> found = statementRepository.findLiveWithoutFileByAccountIdAndPeriod(
                    accountId, draft.periodStart(), draft.periodEnd());
            if (found.isEmpty() || !sameStatementId.equals(found.get().getId())) {
                return false;
            }
            if (statementRepository.existsByAccountIdAndFileSha256(accountId, sha256)) {
                return false;
            }
            Optional<UUID> fileId = store.store(userId, bytes, originalName);
            if (fileId.isEmpty()) {
                return false;
            }
            boolean attached = Boolean.TRUE.equals(tx.execute(status ->
                    statementRepository.setFileAndShaIfMissing(sameStatementId, fileId.get(), sha256) == 1));
            if (attached) {
                log.info("STATEMENT_FILE_COPY_ATTACHED statement={}", sameStatementId);
            }
            return attached;
        } catch (DataIntegrityViolationException e) {
            return false;
        } catch (Exception e) {
            log.warn("STATEMENT_FILE_ATTACH_FAILED {}", e.toString());
            return false;
        }
    }

    /** How many figures could be compared and which of them differ. */
    public record FigureProof(int compared, List<String> differing) {
        /** At least two figures present on both sides, none differing. */
        public boolean holds() {
            return compared >= 2 && differing.isEmpty();
        }
    }

    /** Compares the stored statement's figures with the draft's; a figure counts only when present on both sides. */
    static FigureProof proveSameFigures(Statement stored, StatementDraft draft) {
        List<String> differing = new ArrayList<>();
        int compared = 0;
        BigDecimal[][] pairs = {
                {stored.getOpeningBalance(), draft.openingBalance()},
                {stored.getClosingBalance(), draft.closingBalance()},
                {stored.getTotalDebits(), draft.totalDebits()},
                {stored.getTotalCredits(), draft.totalCredits()},
                {stored.getCreditCardDetails() == null ? null : stored.getCreditCardDetails().getTotalAmountDue(),
                        toDecimal(draft.cardFields() == null ? null : draft.cardFields().get("total_amount_due"))},
        };
        String[] names = {"opening_balance", "closing_balance", "total_debits", "total_credits", "total_amount_due"};
        boolean closingCompared = pairs[1][0] != null && pairs[1][1] != null;
        for (int i = 0; i < pairs.length; i++) {
            if (pairs[i][0] == null || pairs[i][1] == null) {
                continue;
            }
            // On cards the closing balance is the total due: one figure, not two.
            if (i == 4 && closingCompared) {
                continue;
            }
            compared++;
            if (pairs[i][0].compareTo(pairs[i][1]) != 0) {
                differing.add(names[i]);
            }
        }
        return new FigureProof(compared, differing);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private AttachResult setIfMissing(UUID statementId, UUID fileId) {
        return tx.execute(status -> {
            Optional<Statement> found = statementRepository.findById(statementId);
            if (found.isEmpty()) {
                return AttachResult.STATEMENT_MISSING;
            }
            Statement s = found.get();
            if (s.getFileId() != null) {
                return AttachResult.ALREADY_HAD_FILE;
            }
            s.setFileId(fileId);
            statementRepository.save(s);
            return AttachResult.ATTACHED;
        });
    }
}
