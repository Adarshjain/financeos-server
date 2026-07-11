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
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.categorization.CategorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private final GmailIngestProperties ingestProperties;
    private final ReviewStatusManager reviewStatusManager;
    private final CategorizationService categorizationService;

    public FileIngestionService(AccountRepository accountRepository,
                                UserRepository userRepository,
                                StatementParser statementParser,
                                FileIngestionDbHandler dbHandler,
                                TransactionMatcher transactionMatcher,
                                GmailIngestProperties ingestProperties,
                                ReviewStatusManager reviewStatusManager,
                                CategorizationService categorizationService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.statementParser = statementParser;
        this.dbHandler = dbHandler;
        this.transactionMatcher = transactionMatcher;
        this.ingestProperties = ingestProperties;
        this.reviewStatusManager = reviewStatusManager;
        this.categorizationService = categorizationService;
    }

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
        LocalDate maxEffectiveEnd = null;

        // Loop over files to parse them (Gemini I/O runs here outside the write transaction)
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

                String filenameLower = filename.toLowerCase();
                String textContent = null;

                if (filenameLower.endsWith(".pdf")) {
                    try {
                        // Attempt to extract without a password first
                        textContent = statementParser.extractTextFromPdf(bytes, null);
                    } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                        // Fallback to password decrypt check if configured
                        if (password == null || password.trim().isEmpty()) {
                            throw new ValidationException("File is password-protected, but no statement password is configured for this account.");
                        }
                        try {
                            textContent = statementParser.extractTextFromPdf(bytes, password);
                        } catch (Exception ex) {
                            throw new ValidationException("Failed to decrypt PDF statement with the configured password: " + ex.getMessage());
                        }
                    }
                } else if (filenameLower.endsWith(".xlsx") || filenameLower.endsWith(".xls")) {
                    try {
                        // Attempt to extract without a password first
                        textContent = statementParser.extractTextFromExcel(bytes, null);
                    } catch (org.apache.poi.EncryptedDocumentException e) {
                        // Fallback to password decrypt check if configured
                        if (password == null || password.trim().isEmpty()) {
                            throw new ValidationException("File is password-protected, but no statement password is configured for this account.");
                        }
                        try {
                            textContent = statementParser.extractTextFromExcel(bytes, password);
                        } catch (Exception ex) {
                            throw new ValidationException("Failed to decrypt Excel statement with the configured password: " + ex.getMessage());
                        }
                    }
                } else {
                    throw new ValidationException("Unsupported file type. Only PDF and Excel files are supported.");
                }

                com.financeos.gmail.reconcile.StatementExtractionResult parseResult = statementParser.parse(textContent);
                if (!parseResult.success()) {
                    throw new ValidationException("Failed to parse statement using Gemini: " + parseResult.failureReason());
                }

                LocalDate effectiveEnd = null;
                if (parseResult.statementPeriodEnd() != null && !parseResult.statementPeriodEnd().trim().isEmpty()) {
                    try {
                        effectiveEnd = LocalDate.parse(parseResult.statementPeriodEnd().trim());
                    } catch (Exception e) {
                        log.warn("Failed to parse statementPeriodEnd '{}' as date for file {}, falling back to max line date",
                                parseResult.statementPeriodEnd(), filename, e);
                    }
                }
                if (effectiveEnd == null) {
                    log.warn("Statement period end missing or invalid in file {}; falling back to max line date", filename);
                    effectiveEnd = parseResult.lines().stream()
                            .map(com.financeos.gmail.reconcile.ParsedStatementLine::date)
                            .max(LocalDate::compareTo)
                            .orElse(null);
                }
                if (effectiveEnd != null) {
                    if (maxEffectiveEnd == null || effectiveEnd.isAfter(maxEffectiveEnd)) {
                        maxEffectiveEnd = effectiveEnd;
                    }
                }

                List<com.financeos.gmail.reconcile.ParsedStatementLine> lines = parseResult.lines();
                if (lines == null || lines.isEmpty()) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", 0, "No transactions found"));
                    filesProcessed++;
                    continue;
                }

                for (com.financeos.gmail.reconcile.ParsedStatementLine line : lines) {
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
                }

                fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", lines.size(), null));
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
