package com.financeos.domain.ingestion;

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
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class FileIngestionService {

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

    private record PendingLink(Transaction txn, UUID statementId, int lineIndex, BigDecimal balanceAfter, Boolean chainValid) {}

    public FileIngestionResult ingest(UUID accountId, List<MultipartFile> files) {
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
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                filename = "unknown_file";
            }

            try {
                byte[] bytes = file.getBytes();
                if (bytes.length == 0) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "FAILED", 0, "File is empty"));
                    continue;
                }

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

                String fileMessage = null;
                String fragment = account.getBankDetails() != null ? account.getBankDetails().getLast4()
                        : account.getCreditCardDetails() != null ? account.getCreditCardDetails().getLast4() : null;
                if (parseResult.accountNumber() != null && fragment != null) {
                    String normalizedNumber = parseResult.accountNumber().replaceAll("\\s+", "").toLowerCase();
                    String normalizedFragment = fragment.replaceAll("\\s+", "").toLowerCase();
                    if (!normalizedNumber.contains(normalizedFragment)) {
                        log.warn("Statement account number '{}' does not match account {} (file {})",
                                parseResult.accountNumber(), account.getId(), filename);
                        fileMessage = "Warning: statement account number does not match this account";
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

                UUID statementId = stmt.get().getId();
                for (int i = 0; i < lines.size(); i++) {
                    ParsedStatementLine line = lines.get(i);
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
                    pendingLinks.add(new PendingLink(txn, statementId, i, line.balance(), line.chainValid()));
                }

                fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", lines.size(), fileMessage));
                filesProcessed++;

            } catch (Exception e) {
                log.error("Failed to process file: {}", filename, e);
                fileDetails.add(new FileIngestionResult.FileSummary(filename, "FAILED", 0, e.getMessage()));
            }
        }

        // Perform duplicate checking and save transactions in a short, dedicated database transaction
        int totalDuplicatesFound = 0;
        if (!newTransactionsToInsert.isEmpty()) {
            LocalDate minDate = newTransactionsToInsert.stream().map(Transaction::getDate).min(LocalDate::compareTo).get();
            LocalDate maxDate = newTransactionsToInsert.stream().map(Transaction::getDate).max(LocalDate::compareTo).get();

            // Load candidates inside a read-only transaction boundary
            List<Transaction> dbTxns = dbHandler.findExistingTransactions(account.getId(), minDate, maxDate);
            Set<Transaction> dbTxnsToUpdate = new HashSet<>();
            Set<Transaction> duplicateNewTxns = new HashSet<>();

            int dateWindow = 0; // Same day only as requested

            for (int i = 0; i < newTransactionsToInsert.size(); i++) {
                Transaction newTx = newTransactionsToInsert.get(i);

                // Check duplicates against DB using shared TransactionMatcher
                for (Transaction dbTx : dbTxns) {
                    if (transactionMatcher.areDuplicates(newTx, dbTx, dateWindow)) {
                        duplicateNewTxns.add(newTx);
                        reviewStatusManager.addReason(dbTx, ReviewReason.DUPLICATE_SUSPECT);
                        dbTxnsToUpdate.add(dbTx);
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
        }

        if (maxEffectiveEnd != null) {
            LocalDate existing = account.getLastStatementDate();
            if (existing == null || maxEffectiveEnd.isAfter(existing)) {
                account.setLastStatementDate(maxEffectiveEnd);
                accountRepository.save(account);
            }
        }

        return new FileIngestionResult(
                filesProcessed,
                newTransactionsToInsert.size(),
                totalDuplicatesFound,
                fileDetails
        );
    }
}
