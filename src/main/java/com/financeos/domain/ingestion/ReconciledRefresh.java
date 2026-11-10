package com.financeos.domain.ingestion;

import com.financeos.domain.account.Account;
import com.financeos.domain.categorization.CategoryAssigner;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.domain.user.User;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a reconciling re-read does to a stored statement, in one piece shared by the upload path and the re-read job:
 * {@link StatementPersistenceService#refreshReconciled}, then {@link StatementBatchMatcher#match} for the lines no linked
 * transaction accounts for, creating the file_upload rows ({@link #createRows}). Run it inside the caller's transaction.
 */
@Component
public class ReconciledRefresh {

    private static final Logger log = LoggerFactory.getLogger(ReconciledRefresh.class);

    /** The refresh and, when lines were missing, the matcher's result (null otherwise). */
    public record Applied(StatementPersistenceService.Refreshed refreshed, StatementBatchMatcher.Result matched) {}

    private final StatementPersistenceService statementPersistenceService;
    private final StatementBatchMatcher batchMatcher;
    private final ReviewStatusManager reviewStatusManager;
    private final FileIngestionDbHandler dbHandler;
    private final CategoryAssigner categoryAssigner;

    @Autowired
    public ReconciledRefresh(StatementPersistenceService statementPersistenceService, StatementBatchMatcher batchMatcher,
                             ReviewStatusManager reviewStatusManager, FileIngestionDbHandler dbHandler,
                             ObjectProvider<CategoryAssigner> categoryAssigner) {
        this(statementPersistenceService, batchMatcher, reviewStatusManager, dbHandler, categoryAssigner.getIfAvailable());
    }

    /** For callers that hold the collaborators already (any may be null where the old path tolerated it). */
    public ReconciledRefresh(StatementPersistenceService statementPersistenceService, StatementBatchMatcher batchMatcher,
                             ReviewStatusManager reviewStatusManager, FileIngestionDbHandler dbHandler,
                             CategoryAssigner categoryAssigner) {
        this.statementPersistenceService = statementPersistenceService;
        this.batchMatcher = batchMatcher;
        this.reviewStatusManager = reviewStatusManager;
        this.dbHandler = dbHandler;
        this.categoryAssigner = categoryAssigner;
    }

    /** Empty when there is nothing to refresh (the parse does not reconcile, or no statement matches). */
    public Optional<Applied> apply(User user, Account account, UUID accountId, String sha256, StatementDraft draft,
                                   List<ParsedStatementLine> lines, Set<UUID> skipStatementIds) {
        Optional<StatementPersistenceService.Refreshed> refreshed = statementPersistenceService.refreshReconciled(
                accountId, sha256, draft, lines, skipStatementIds);
        if (refreshed == null || refreshed.isEmpty()) {
            return Optional.empty();
        }
        StatementPersistenceService.Refreshed r = refreshed.get();
        StatementBatchMatcher.Result matched = null;
        if (!r.missingLines().isEmpty()) {
            List<StatementBatchMatcher.Line> batchLines = new ArrayList<>();
            for (int idx : r.missingLines()) {
                batchLines.add(StatementBatchMatcher.Line.of(idx, lines.get(idx)));
            }
            matched = batchMatcher.match(r.statement(), account, batchLines,
                    unpaired -> createRows(user, account, lines, unpaired));
        }
        return Optional.of(new Applied(r, matched));
    }

    /** The upload's own rows for the lines matching left unpaired: Confirmed, categorised on arrival, then saved. */
    public Map<Integer, Transaction> createRows(User user, Account account, List<ParsedStatementLine> lines,
                                                List<StatementBatchMatcher.Line> unpaired) {
        Map<Integer, Transaction> made = new LinkedHashMap<>();
        for (StatementBatchMatcher.Line batchLine : unpaired) {
            ParsedStatementLine line = lines.get(batchLine.lineIndex());
            Transaction txn = new Transaction();
            txn.setUser(user);
            txn.setAccount(account);
            txn.setDate(line.date());
            txn.setAmount(line.amount().abs());
            txn.setSourcedDescription(line.description());
            txn.setSource(TransactionSource.file_upload);
            txn.setType(TransactionType.fromLlmDirection(line.direction()));
            reviewStatusManager.transitionTo(txn, ReviewType.AUTO_REVIEWED);
            txn.setTransactionUnderMonitoring(false);
            txn.setTransactionExcluded(false);
            made.put(batchLine.lineIndex(), txn);
        }
        List<Transaction> rows = new ArrayList<>(made.values());
        // Each new row gets its category once, here (rules, links, engine), before it is stored.
        if (categoryAssigner != null && !rows.isEmpty()) {
            try {
                int assigned = categoryAssigner.assignOnArrival(rows);
                log.info("Categorised {} of {} uploaded transaction(s) on arrival", assigned, rows.size());
            } catch (Exception e) {
                log.warn("Categorise-on-arrival failed for an upload: {}", e.getMessage());
            }
        }
        dbHandler.saveTransactions(rows, List.of());
        return made;
    }
}
