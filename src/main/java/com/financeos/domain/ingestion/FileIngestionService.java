package com.financeos.domain.ingestion;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.domain.statement.MissingDetails;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewStatusManager;
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

    /** Categorise-on-arrival: optional so tests that build the service by hand need none. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.financeos.domain.categorization.CategoryAssigner categoryAssigner;
    private final StatementPersistenceService statementPersistenceService;
    private final com.financeos.domain.account.AccountService accountService;

    /** Other saved passwords and saving a working one; optional so hand-built test instances need none. */
    private StatementPasswordVault passwordVault;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPasswordVault(StatementPasswordVault passwordVault) {
        this.passwordVault = passwordVault;
    }
    private com.financeos.statement.details.StatementDetailsAiFiller detailsAiFiller;

    /** Optional: absent in plain unit tests, where no AI step runs. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDetailsAiFiller(com.financeos.statement.details.StatementDetailsAiFiller detailsAiFiller) {
        this.detailsAiFiller = detailsAiFiller;
    }

    public FileIngestionService(AccountRepository accountRepository,
                                UserRepository userRepository,
                                StatementParser statementParser,
                                FileIngestionDbHandler dbHandler,
                                TransactionMatcher transactionMatcher,
                                ReviewStatusManager reviewStatusManager,
                                CategorizationService categorizationService,
                                StatementPersistenceService statementPersistenceService,
                                com.financeos.domain.account.AccountService accountService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.statementParser = statementParser;
        this.dbHandler = dbHandler;
        this.transactionMatcher = transactionMatcher;
        this.reviewStatusManager = reviewStatusManager;
        this.categorizationService = categorizationService;
        this.statementPersistenceService = statementPersistenceService;
        this.accountService = accountService;
    }

    /** Set by Spring; tests that build the service by hand and never reach matching need none. */
    private StatementBatchMatcher batchMatcher;

    @org.springframework.beans.factory.annotation.Autowired
    public void setBatchMatcher(StatementBatchMatcher batchMatcher) {
        this.batchMatcher = batchMatcher;
    }

    /** A parsed file whose statement was stored; its lines are matched after every file is read. */
    private record PendingFile(int fileIndex, Statement statement, List<ParsedStatementLine> lines) {}

    public FileIngestionResult ingest(UUID accountId, List<UploadedFile> files) {
        return ingest(accountId, files, null);
    }

    public FileIngestionResult ingest(UUID accountId, List<UploadedFile> files, com.financeos.domain.job.JobExecutionContext execCtx) {
        return ingest(accountId, files, execCtx, null);
    }

    /** What opening a file took: the parse result, whether a password was tried, and the one that worked (null = none or the account's own). */
    private record Opened(StatementExtractionResult result, boolean passwordTried) {}

    /**
     * Parses with the explicit password (if any), else the account's; when the file is locked and that fails,
     * tries the user's other saved statement passwords. A password that opens a file for an account with none
     * is saved to that account. Passwords are never logged.
     */
    private Opened open(UUID userId, UUID accountId, byte[] bytes, String explicit, String accountPassword) {
        boolean hasExplicit = explicit != null && !explicit.isBlank();
        boolean hasAccount = accountPassword != null && !accountPassword.isBlank();
        String first = hasExplicit ? explicit : hasAccount ? accountPassword : null;
        StatementExtractionResult result = statementParser.parse(bytes, first);
        boolean tried = first != null;
        if (result.success() || !com.financeos.statement.parser.StatementParseException.isPasswordMessage(result.failureReason())) {
            return new Opened(result, tried);
        }
        java.util.LinkedHashSet<String> others = new java.util.LinkedHashSet<>();
        if (hasExplicit && hasAccount) others.add(accountPassword);
        if (passwordVault != null) others.addAll(passwordVault.savedPasswords(userId));
        others.remove(first);
        for (String candidate : others) {
            StatementExtractionResult attempt = statementParser.parse(bytes, candidate);
            tried = true;
            if (attempt.success()) {
                if (!hasAccount && passwordVault != null) {
                    passwordVault.saveIfMissing(userId, accountId, candidate);
                }
                return new Opened(attempt, true);
            }
        }
        return new Opened(result, tried);
    }

    /** {@code explicitPassword}: a password the user just typed for these files; tried before the account's. */
    public FileIngestionResult ingest(UUID accountId, List<UploadedFile> files, com.financeos.domain.job.JobExecutionContext execCtx,
                                      String explicitPassword) {
        // Read account (retrieves account, checks permissions, initializes cardholders)
        Account account = accountService.getAccountById(accountId);
        UUID currentSessionUserId = com.financeos.core.security.UserContext.getCurrentUserId();

        User user = userRepository.getReferenceById(currentSessionUserId);

        // Fetch any encrypted password if the account has it configured
        String password = null;
        if (account.getBankDetails() != null) {
            password = account.getBankDetails().getStatementPassword();
        } else if (account.getCreditCardDetails() != null) {
            password = account.getCreditCardDetails().getStatementPassword();
        }

        int filesProcessed = 0;
        List<FileIngestionResult.FileSummary> fileDetails = new ArrayList<>();
        List<PendingFile> pendingFiles = new ArrayList<>();
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
                    fileDetails.add(FileIngestionResult.FileSummary.failed(filename, "File is empty", FileIngestionResult.ErrorCode.UNREADABLE));
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

                Opened opened = open(currentSessionUserId, accountId, bytes, explicitPassword, password);
                StatementExtractionResult parseResult = opened.result();
                if (!parseResult.success()) {
                    fileDetails.add(FileIngestionResult.FileSummary.failed(filename, parseResult.failureReason(),
                            FileIngestionResult.ErrorCode.classify(parseResult.failureReason(), opened.passwordTried())));
                    continue;
                }
                if (detailsAiFiller != null && parseResult.draft() != null) {
                    // No transaction is open here; the filler never throws on LLM problems.
                    parseResult = parseResult.withDraft(detailsAiFiller.fill(currentSessionUserId, parseResult.draft(), parseResult.summaryText()));
                }

                List<ParsedStatementLine> lines = parseResult.lines();
                if (lines == null || lines.isEmpty()) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", 0, "No transactions found"));
                    filesProcessed++;
                    continue;
                }

                String sha256 = StatementPersistenceService.sha256Hex(bytes);
                Optional<List<String>> filled = statementPersistenceService.refreshFromReupload(accountId, sha256, parseResult.draft());
                if (filled.isPresent()) {
                    List<String> fields = filled.get();
                    String message = fields.isEmpty()
                            ? "Statement already ingested; nothing new to fill"
                            : "Statement already ingested; filled in " + String.join(", ", fields.stream().map(MissingDetails::words).toList());
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SKIPPED", 0, message, null, 0, 0, fields));
                    filesProcessed++;
                    continue;
                }

                Optional<Statement> stmt = statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                        filename, sha256, parseResult.draft());
                if (stmt.isEmpty()) {
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SKIPPED", 0, "Statement already ingested (same period or file)"));
                    filesProcessed++;
                    continue;
                }

                String warningMessage = null;
                String fragment = accountService.extractLast4(account);
                if (parseResult.accountNumber() != null && fragment != null) {
                    String normalizedNumber = parseResult.accountNumber().replaceAll("\\s+", "").toLowerCase();
                    String normalizedFragment = fragment.replaceAll("\\s+", "").toLowerCase();
                    if (!normalizedNumber.contains(normalizedFragment)) {
                        log.warn("Statement account number '{}' does not match account {} (file {})",
                                parseResult.accountNumber(), account.getId(), filename);
                        warningMessage = "Warning: statement account number does not match this account";
                    }
                }
                String reconciliation = reconciliationWarning(parseResult.draft());
                if (reconciliation != null) {
                    warningMessage = warningMessage == null ? reconciliation : warningMessage + ". " + reconciliation;
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
                pendingFiles.add(new PendingFile(currentFileIndex, stmt.get(), lines));

                fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", lines.size(), null, warningMessage, 0, 0));
                filesProcessed++;

            } catch (Exception e) {
                log.error("Failed to process file: {}", filename, e);
                fileDetails.add(FileIngestionResult.FileSummary.failed(filename, e.getMessage(),
                        FileIngestionResult.ErrorCode.classify(e.getMessage(), false)));
            }
        }

        // Match each statement as a whole, file by file (a later file of the same period attaches to the rows the
        // earlier one created). Lines nothing pairs with become file_upload rows; identical lines of one file are all real.
        int totalCreated = 0;
        for (PendingFile pf : pendingFiles) {
            if (batchMatcher == null) {
                throw new IllegalStateException("StatementBatchMatcher is not available");
            }
            List<StatementBatchMatcher.Line> batchLines = new ArrayList<>();
            for (int j = 0; j < pf.lines().size(); j++) {
                batchLines.add(StatementBatchMatcher.Line.of(j, pf.lines().get(j)));
            }
            StatementBatchMatcher.Result matched = batchMatcher.match(pf.statement(), account, batchLines,
                    unpaired -> createRows(user, account, pf.lines(), unpaired));
            totalCreated += matched.created();
            FileIngestionResult.FileSummary summary = fileDetails.get(pf.fileIndex());
            fileDetails.set(pf.fileIndex(), summary.withCounts(matched.created(), 0));
            log.info("Upload {} matched: {} line(s) attached to existing rows, {} created, {} alert(s) not on the statement",
                    summary.filename(), matched.matched(), matched.created(), matched.tagged());
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
                totalCreated,
                0,
                fileDetails,
                List.of(),
                0
        );
    }

    /** The upload's own rows for the lines matching left unpaired: Confirmed, categorised on arrival, then saved. */
    private Map<Integer, Transaction> createRows(User user, Account account, List<ParsedStatementLine> lines,
                                                 List<StatementBatchMatcher.Line> unpaired) {
        Map<Integer, Transaction> made = new java.util.LinkedHashMap<>();
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

    // The parser checks its rows against the statement's own opening and closing
    // balances. When they don't reconcile, rows were missed or misread — the
    // transactions are still created, but the user has to know to check them.
    static String reconciliationWarning(StatementDraft draft) {
        if (draft == null) {
            return null;
        }
        boolean unreconciled = Boolean.FALSE.equals(draft.checksumOk());
        if (!unreconciled && draft.verdict() != StatementVerdict.REJECTED) {
            return null;
        }
        StringBuilder sb = new StringBuilder(unreconciled
                ? "Statement totals don't add up: the transactions found don't take the opening balance to the closing balance"
                : "These transactions couldn't be verified against the statement's balances");
        int skipped = draft.linesSkipped();
        if (skipped > 0) {
            sb.append(", and ").append(skipped).append(skipped == 1 ? " row" : " rows").append(" couldn't be read");
        }
        return sb.append(". Some may be missing or wrong; check them against the statement.").toString();
    }
}

