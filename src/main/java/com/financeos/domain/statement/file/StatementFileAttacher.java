package com.financeos.domain.statement.file;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
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

    private void setIfMissing(UUID statementId, UUID fileId) {
        tx.executeWithoutResult(status -> statementRepository.findById(statementId).ifPresent((Statement s) -> {
            if (s.getFileId() == null) {
                s.setFileId(fileId);
                statementRepository.save(s);
            }
        }));
    }
}
