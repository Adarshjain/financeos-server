package com.financeos.domain.ingestion;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.categorization.CategorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class FileIngestionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileIngestionService.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final StatementParser statementParser;
    private final FileIngestionDbHandler dbHandler;
    private final TransactionMatcher transactionMatcher;
    private final ReviewStatusManager reviewStatusManager;
    private final CategorizationService categorizationService;
    private final StatementPersistenceService statementPersistenceService;

    public FileIngestionService(AccountRepository accountRepository,
                                UserRepository userRepository,
                                StatementParser statementParser,
                                FileIngestionDbHandler dbHandler,
                                TransactionMatcher transactionMatcher,
                                ReviewStatusManager reviewStatusManager,
                                CategorizationService categorizationService,
                                StatementPersistenceService statementPersistenceService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.statementParser = statementParser;
        this.dbHandler = dbHandler;
        this.transactionMatcher = transactionMatcher;
        this.reviewStatusManager = reviewStatusManager;
        this.categorizationService = categorizationService;
        this.statementPersistenceService = statementPersistenceService;
    }

    private record PendingLink(Transaction txn, UUID statementId, int lineIndex, BigDecimal balanceAfter, Boolean chainValid, int fileIndex) {}

    public FileIngestionResult ingest(UUID accountId, List<UploadedFile> files) {
        return ingest(accountId, files, null);
    }

    public FileIngestionResult ingest(UUID accountId, List<UploadedFile> files, com.financeos.domain.job.JobExecutionContext execCtx) {
        // Read account (this does not need a long-lived transaction)
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));

        // SECURITY: Verify that the account belongs to the session user.
        UUID currentSessionUserId = com.financeos.core.security.UserContext.getCurrentUserId();
        if (!account.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to ingest files to Account {} owned by User {}",
                    currentSessionUserId, account.getId(), account.getUser().getId());
            throw new ValidationException("You do not have permission to ingest files to this account.");
        }

        User user = userRepository.getReferenceById(currentSessionUserId);

        // Fetch any encrypted password if the account has it configured
        String password = null;
        if (account.getBankDetails() != null) {
            password = account.getBankDetails().getStatementPassword();
        } else if (account.getCreditCardDetails() != null) {
            password = account.getCreditCardDetails().getStatementPassword();
        }

        int filesProcessed = 0;
        List<Transaction> newTransactionsToInsert = new ArrayList<>();
        List<FileIngestionResult.FileSummary> fileDetails = new ArrayList<>();
        List<PendingLink> pendingLinks = new ArrayList<>();
        LocalDate maxEffectiveEnd = null;

        // Loop over files to parse them (statement parsing runs here outside the write transaction)
        for (int i = 0; i < files.size(); i++) {
            UploadedFile file = files.get(i);
            if (execCtx != null) {
                execCtx.checkCancelled();
                execCtx.progress(i, files.size(), file.filename());
            }

            String filename = file.filename();
            if (filename == null || filename.isBlank()) {
                filename = "unknown_file";
            }

            try {
                byte[] bytes = file.bytes() != null ? file.bytes() : new byte[0];
                if (bytes.length == 0) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "FAILED", 0, "File is empty"));
                    continue;
                }

                log.info("Ingest file received: fileName={}, sizeBytes={}", filename, bytes.length,
                        StructuredArguments.keyValue("event", Events.INGEST_FILE_RECEIVED),
                        StructuredArguments.keyValue("fileName", filename),
                        StructuredArguments.keyValue("sizeBytes", bytes.length),
                        StructuredArguments.keyValue("contentType", file.contentType() != null ? file.contentType() : "application/octet-stream"),
                        StructuredArguments.keyValue("sha256", ""),
                        StructuredArguments.keyValue("parserChosen", statementParser.getClass().getSimpleName()),
                        StructuredArguments.keyValue("brokerHint", account.getType().name()));

                StatementExtractionResult parseResult = statementParser.parse(bytes, password);
                if (!parseResult.success()) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "FAILED", 0, parseResult.failureReason()));
                    continue;
                }

                List<ParsedStatementLine> lines = parseResult.lines();
                if (lines == null || lines.isEmpty()) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", 0, "No transactions found"));
                    filesProcessed++;
                    continue;
                }

                Optional<Statement> stmt = statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                        filename, StatementPersistenceService.sha256Hex(bytes), parseResult.draft());
                if (stmt.isEmpty()) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SKIPPED", 0, "Statement already ingested (same period or file)"));
                    filesProcessed++;
                    continue;
                }

                String warningMessage = null;
                String fragment = account.getBankDetails() != null ? account.getBankDetails().getLast4()
                        : account.getCreditCardDetails() != null ? account.getCreditCardDetails().getLast4() : null;
                if (parseResult.accountNumber() != null && fragment != null) {
                    String normalizedNumber = parseResult.accountNumber().replaceAll("\\s+", "").toLowerCase();
                    String normalizedFragment = fragment.replaceAll("\\s+", "").toLowerCase();
                    if (!normalizedNumber.contains(normalizedFragment)) {
                        log.warn("Statement account number '{}' does not match account {} (file {})",
                                parseResult.accountNumber(), account.getId(), filename);
                        warningMessage = "Warning: statement account number does not match this account";
                    }
                }

                LocalDate effectiveEnd = parseResult.periodEnd();
                if (effectiveEnd == null) {
                    log.warn("Statement period end missing in file {}; falling back to max line date", filename);
                    effectiveEnd = lines.stream()
                            .map(ParsedStatementLine::date)
                            .max(LocalDate::compareTo)
                            .orElse(null);
                }
                if (effectiveEnd != null) {
                    if (maxEffectiveEnd == null || effectiveEnd.isAfter(maxEffectiveEnd)) {
                        maxEffectiveEnd = effectiveEnd;
                    }
                }

                int currentFileIndex = fileDetails.size();
                UUID statementId = stmt.get().getId();
                for (int j = 0; j < lines.size(); j++) {
                    ParsedStatementLine line = lines.get(j);
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

                    newTransactionsToInsert.add(txn);
                    pendingLinks.add(new PendingLink(txn, statementId, j, line.balance(), line.chainValid(), currentFileIndex));
                }

                fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", lines.size(), null, warningMessage, 0, 0));
                filesProcessed++;

            } catch (Exception e) {
                log.error("Failed to process file: {}", filename, e);
                fileDetails.add(new FileIngestionResult.FileSummary(filename, "FAILED", 0, e.getMessage()));
            }
        }

        // Perform duplicate checking and save transactions in a short, dedicated database transaction
        int totalDuplicatesFound = 0;
        List<FileIngestionResult.DuplicateDetail> duplicateDetails = new ArrayList<>();
        int duplicatesTruncated = 0;

        if (!newTransactionsToInsert.isEmpty()) {
            LocalDate minDate = newTransactionsToInsert.stream().map(Transaction::getDate).min(LocalDate::compareTo).get();
            LocalDate maxDate = newTransactionsToInsert.stream().map(Transaction::getDate).max(LocalDate::compareTo).get();

            // Load candidates inside a read-only transaction boundary
            List<Transaction> dbTxns = dbHandler.findExistingTransactions(account.getId(), minDate, maxDate);
            Set<Transaction> dbTxnsToUpdate = new HashSet<>();
            Set<Transaction> duplicateNewTxns = new HashSet<>();
            Map<Transaction, UUID> matchedDbTxnIds = new HashMap<>();

            int dateWindow = 0; // Same day only as requested

            for (int i = 0; i < newTransactionsToInsert.size(); i++) {
                Transaction newTx = newTransactionsToInsert.get(i);

                // Check duplicates against DB using shared TransactionMatcher
                for (Transaction dbTx : dbTxns) {
                    if (transactionMatcher.areDuplicates(newTx, dbTx, dateWindow)) {
                        duplicateNewTxns.add(newTx);
                        reviewStatusManager.addReason(dbTx, ReviewReason.DUPLICATE_SUSPECT);
                        dbTxnsToUpdate.add(dbTx);
                        matchedDbTxnIds.putIfAbsent(newTx, dbTx.getId());
                    }
                }

                // Check duplicates within the uploaded batch
                for (int j = 0; j < newTransactionsToInsert.size(); j++) {
                    if (i == j) continue;
                    Transaction otherNewTx = newTransactionsToInsert.get(j);
                    if (transactionMatcher.areDuplicates(newTx, otherNewTx, dateWindow)) {
                        duplicateNewTxns.add(newTx);
                        duplicateNewTxns.add(otherNewTx);
                    }
                }
            }

            // Flag duplicate new transactions
            for (Transaction newTx : duplicateNewTxns) {
                reviewStatusManager.addReason(newTx, ReviewReason.DUPLICATE_SUSPECT);
            }

            totalDuplicatesFound = duplicateNewTxns.size();

            // Categorize transactions before persisting
            categorizationService.batchCategorize(newTransactionsToInsert);

            // Persist changes inside a write transaction boundary
            dbHandler.saveTransactions(newTransactionsToInsert, new ArrayList<>(dbTxnsToUpdate));

            Map<UUID, List<StatementPersistenceService.TxnLink>> linksByStatement = new HashMap<>();
            for (PendingLink pl : pendingLinks) {
                linksByStatement.computeIfAbsent(pl.statementId(), k -> new ArrayList<>())
                        .add(new StatementPersistenceService.TxnLink(pl.txn().getId(), pl.lineIndex(), pl.balanceAfter(), pl.chainValid()));
            }
            for (Map.Entry<UUID, List<StatementPersistenceService.TxnLink>> entry : linksByStatement.entrySet()) {
                statementPersistenceService.linkTransactions(entry.getKey(), entry.getValue());
            }

            // Rebuild fileDetails for SUCCESS entries with created & duplicates counts
            Map<Transaction, PendingLink> txnLinkMap = new HashMap<>();
            Map<Integer, List<PendingLink>> linksByFileIndex = new HashMap<>();
            for (PendingLink pl : pendingLinks) {
                txnLinkMap.put(pl.txn(), pl);
                linksByFileIndex.computeIfAbsent(pl.fileIndex(), k -> new ArrayList<>()).add(pl);
            }

            for (int k = 0; k < fileDetails.size(); k++) {
                FileIngestionResult.FileSummary summary = fileDetails.get(k);
                if ("SUCCESS".equals(summary.status())) {
                    List<PendingLink> fileLinks = linksByFileIndex.getOrDefault(k, List.of());
                    int createdForFile = fileLinks.size();
                    int dupsForFile = 0;
                    for (PendingLink pl : fileLinks) {
                        if (duplicateNewTxns.contains(pl.txn())) {
                            dupsForFile++;
                        }
                    }
                    fileDetails.set(k, new FileIngestionResult.FileSummary(
                            summary.filename(),
                            summary.status(),
                            summary.linesParsed(),
                            summary.errorMessage(),
                            summary.warning(),
                            createdForFile,
                            dupsForFile
                    ));
                }
            }

            // Build duplicateDetails capped at 50
            List<Transaction> sortedDupTxns = new ArrayList<>(duplicateNewTxns);
            sortedDupTxns.sort(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(LocalDate::compareTo))
                    .thenComparing(Transaction::getAmount, Comparator.nullsLast(BigDecimal::compareTo)));

            int limit = Math.min(sortedDupTxns.size(), 50);
            for (int k = 0; k < limit; k++) {
                Transaction dupTx = sortedDupTxns.get(k);
                PendingLink pl = txnLinkMap.get(dupTx);
                String filename = (pl != null && pl.fileIndex() < fileDetails.size())
                        ? fileDetails.get(pl.fileIndex()).filename() : "unknown_file";
                UUID matchedTxId = matchedDbTxnIds.get(dupTx);

                duplicateDetails.add(new FileIngestionResult.DuplicateDetail(
                        dupTx.getDate() != null ? dupTx.getDate().toString() : null,
                        dupTx.getAmount(),
                        dupTx.getSourcedDescription(),
                        filename,
                        dupTx.getId(),
                        matchedTxId
                ));
            }
            duplicatesTruncated = Math.max(0, duplicateNewTxns.size() - 50);
        }

        if (maxEffectiveEnd != null) {
            LocalDate existing = account.getLastStatementDate();
            if (existing == null || maxEffectiveEnd.isAfter(existing)) {
                account.setLastStatementDate(maxEffectiveEnd);
                accountRepository.save(account);
            }
        }

        if (execCtx != null) {
            execCtx.progress(files.size(), files.size(), "Completed");
        }

        return new FileIngestionResult(
                filesProcessed,
                newTransactionsToInsert.size(),
                totalDuplicatesFound,
                fileDetails,
                duplicateDetails,
                duplicatesTruncated
        );
    }
}

