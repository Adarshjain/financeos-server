package com.financeos.domain.ingestion;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
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
    private com.financeos.domain.statement.file.StatementFileAttacher fileAttacher;

    /** Keeps the original file of each statement; optional so hand-built test instances need none. Never called inside a transaction. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFileAttacher(com.financeos.domain.statement.file.StatementFileAttacher fileAttacher) {
        this.fileAttacher = fileAttacher;
    }

    private com.financeos.statement.details.StatementDetailsAiFiller detailsAiFiller;

    /** Optional: absent in plain unit tests, where no AI step runs. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDetailsAiFiller(com.financeos.statement.details.StatementDetailsAiFiller detailsAiFiller) {
        this.detailsAiFiller = detailsAiFiller;
    }

    /** Parse records (version + encrypted snapshot), written after the statement's transaction; optional so hand-built test instances need none. */
    private com.financeos.domain.statement.StatementParseRecords parseRecords;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setParseRecords(com.financeos.domain.statement.StatementParseRecords parseRecords) {
        this.parseRecords = parseRecords;
    }

    /** Parse-outcome metric; optional so hand-built test instances need none. */
    private com.financeos.statement.StatementParseMetrics parseMetrics;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setParseMetrics(com.financeos.statement.StatementParseMetrics parseMetrics) {
        this.parseMetrics = parseMetrics;
    }

    private void recordParse(String path, StatementExtractionResult result, Optional<com.financeos.domain.statement.StatementImportGate.Blocked> blocked) {
        if (parseMetrics != null) {
            parseMetrics.record(path, result, blocked);
        }
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

    private StatementOpener.Opened open(UUID userId, UUID accountId, byte[] bytes, String explicit, String accountPassword) {
        return new StatementOpener(statementParser, passwordVault).open(userId, accountId, bytes, explicit, accountPassword, true);
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
        int refreshCreated = 0;
        Set<UUID> handledStatementIds = new HashSet<>();
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

                String accountPassword = password;
                StatementOpener.Opened opened = Timing.call(Ops.STATEMENT_OPEN, () -> open(currentSessionUserId, accountId, bytes, explicitPassword, accountPassword));
                StatementExtractionResult parseResult = opened.result();
                if (!parseResult.success()) {
                    recordParse("upload", parseResult, Optional.empty());
                    fileDetails.add(FileIngestionResult.FileSummary.failed(filename, parseResult.failureReason(),
                            FileIngestionResult.ErrorCode.classify(parseResult.failureKind(), parseResult.failureReason(), opened.passwordTried())));
                    continue;
                }
                if (detailsAiFiller != null && parseResult.draft() != null) {
                    // No transaction is open here; the filler never throws on LLM problems.
                    StatementExtractionResult toFill = parseResult;
                    parseResult = toFill.withDraft(Timing.call(Ops.STATEMENT_DETAILS_FILL, () -> detailsAiFiller.fill(currentSessionUserId, toFill.draft(), toFill.summaryText())));
                }

                List<ParsedStatementLine> lines = parseResult.lines();
                if (lines == null || lines.isEmpty()) {
                    recordParse("upload", parseResult, Optional.empty());
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SUCCESS", 0, "No transactions found"));
                    filesProcessed++;
                    continue;
                }

                // A parse that contradicts its own totals, or a file that is no statement, creates nothing.
                Optional<com.financeos.domain.statement.StatementImportGate.Blocked> blocked =
                        com.financeos.domain.statement.StatementImportGate.check(parseResult);
                recordParse("upload", parseResult, blocked);
                if (blocked.isPresent()) {
                    fileDetails.add(FileIngestionResult.FileSummary.failed(filename, blocked.get().message(),
                            FileIngestionResult.ErrorCode.OTHER));
                    continue;
                }

                String sha256 = StatementPersistenceService.sha256Hex(bytes);
                // A re-upload that fully reconciles corrects the stored statement and adds its missing lines in one transaction.
                // A statement this call already created or refreshed is left to the old path (its lines are not linked yet).
                List<ParsedStatementLine> parsedLines = lines;
                StatementDraft parsedDraft = parseResult.draft();
                RefreshOutcome outcome = inTransaction(() -> reconciledRefresh()
                        .apply(user, account, accountId, sha256, parsedDraft, parsedLines, handledStatementIds)
                        .map(applied -> new RefreshOutcome(applied.refreshed(), applied.matched()))
                        .orElse(null));
                if (outcome != null) {
                    int created = outcome.matched() == null ? 0 : outcome.matched().created();
                    int attached = outcome.matched() == null ? 0 : outcome.matched().matched();
                    handledStatementIds.add(outcome.refreshed().statement().getId());
                    // The refresh applied the new parse and has committed: its record replaces the old one (none for a fill-only refresh, which is another path).
                    if (parsedDraft != null && parseRecords != null) {
                        parseRecords.saveQuietly(outcome.refreshed().statement().getId(), parsedDraft.parserVersion(), parsedDraft.parseSnapshot());
                    }
                    // Only the statement's own file: a refresh matched by period from other bytes must not get this one.
                    if (sha256.equals(outcome.refreshed().statement().getFileSha256())) {
                        attachFile(currentSessionUserId, outcome.refreshed().statement().getId(), bytes, filename);
                    }
                    refreshCreated += created;
                    fileDetails.add(refreshSummary(filename, lines.size(), outcome.refreshed(), created, attached));
                    filesProcessed++;
                    continue;
                }

                Optional<List<String>> filled = statementPersistenceService.refreshFromReupload(accountId, sha256, parseResult.draft());
                if (filled.isPresent()) {
                    List<String> fields = filled.get();
                    attachFileIfLacking(currentSessionUserId, accountId, sha256, bytes, filename);
                    String message = fields.isEmpty()
                            ? "Statement already ingested; nothing new to fill"
                            : "Statement already ingested; filled in " + String.join(", ", fields.stream().map(MissingDetails::words).toList());
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SKIPPED", 0, message, null, 0, 0, fields));
                    filesProcessed++;
                    continue;
                }

                String storedName = filename;
                StatementExtractionResult toStore = parseResult;
                Optional<Statement> stmt;
                try {
                    stmt = Timing.call(Ops.STATEMENT_PERSIST, () -> statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                            storedName, sha256, toStore.draft()));
                } catch (org.springframework.dao.DataIntegrityViolationException race) {
                    // A parallel upload of the same statement won the unique key (account+period or account+file):
                    // same outcome as the existence check catching it. Any other violation keeps failing.
                    if (!statementPersistenceService.alreadyExists(accountId, toStore.draft(), sha256)) {
                        throw race;
                    }
                    log.info("Statement of {} was created by a parallel upload; skipping", filename);
                    stmt = Optional.empty();
                }
                if (stmt.isEmpty()) {
                    attachFileIfLacking(currentSessionUserId, accountId, sha256, bytes, filename);
                    fileDetails.add(new FileIngestionResult.FileSummary(filename, "SKIPPED", 0, "Statement already ingested (same period or file)"));
                    filesProcessed++;
                    continue;
                }

                handledStatementIds.add(stmt.get().getId());
                if (toStore.draft() != null && parseRecords != null) {
                    parseRecords.saveQuietly(stmt.get().getId(), toStore.draft().parserVersion(), toStore.draft().parseSnapshot());
                }
                attachFile(currentSessionUserId, stmt.get().getId(), bytes, filename);
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
                String failure = e instanceof org.springframework.dao.DataAccessException
                        ? "Something went wrong while saving this statement. Please try again."
                        : e.getMessage();
                fileDetails.add(FileIngestionResult.FileSummary.failed(filename, failure,
                        FileIngestionResult.ErrorCode.classify(failure, false)));
            }
        }

        // Match each statement as a whole, file by file (a later file of the same period attaches to the rows the
        // earlier one created). Lines nothing pairs with become file_upload rows; identical lines of one file are all real.
        int totalCreated = refreshCreated;
        for (PendingFile pf : pendingFiles) {
            if (batchMatcher == null) {
                throw new IllegalStateException("StatementBatchMatcher is not available");
            }
            List<StatementBatchMatcher.Line> batchLines = new ArrayList<>();
            for (int j = 0; j < pf.lines().size(); j++) {
                batchLines.add(StatementBatchMatcher.Line.of(j, pf.lines().get(j)));
            }
            StatementBatchMatcher.Result matched = Timing.call(Ops.MATCH_STATEMENT_BATCH, () -> batchMatcher.match(pf.statement(), account, batchLines,
                    unpaired -> createRows(user, account, pf.lines(), unpaired)));
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

    private ReconciledRefresh reconciledRefresh() {
        return new ReconciledRefresh(statementPersistenceService, batchMatcher, reviewStatusManager, dbHandler, categoryAssigner);
    }

    private Map<Integer, Transaction> createRows(User user, Account account, List<ParsedStatementLine> lines,
                                                 List<StatementBatchMatcher.Line> unpaired) {
        return reconciledRefresh().createRows(user, account, lines, unpaired);
    }

    private void attachFile(UUID userId, UUID statementId, byte[] bytes, String filename) {
        if (fileAttacher == null) {
            return;
        }
        try {
            fileAttacher.attach(userId, statementId, bytes, filename);
        } catch (RuntimeException e) {
            log.warn("Keeping the original statement file failed: {}", e.toString());
        }
    }

    private void attachFileIfLacking(UUID userId, UUID accountId, String sha256, byte[] bytes, String filename) {
        if (fileAttacher == null) {
            return;
        }
        try {
            fileAttacher.attachIfStoredStatementLacksFile(userId, accountId, sha256, bytes, filename);
        } catch (RuntimeException e) {
            log.warn("Keeping the original statement file failed: {}", e.toString());
        }
    }

    private record RefreshOutcome(StatementPersistenceService.Refreshed refreshed, StatementBatchMatcher.Result matched) {}

    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** Optional: without it (plain unit tests) the refresh runs without an enclosing transaction. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager manager) {
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactionTemplate == null ? work.get() : transactionTemplate.execute(status -> work.get());
    }

    /** What a refreshed file did, as its summary (and the structured log line); nothing changed = SKIPPED. */
    private FileIngestionResult.FileSummary refreshSummary(String filename, int linesParsed,
                                                           StatementPersistenceService.Refreshed r, int created, int attached) {
        int added = created + attached;
        int extra = r.extraTransactionIds().size();
        log.info("Statement refreshed from re-upload: statementId={}, fields={}, rowsAdded={}, extraRows={}, keptEdited={}", r.statement().getId(),
                r.changedFields(), added, extra, r.keptEdited(),
                StructuredArguments.keyValue("event", Events.STATEMENT_REFRESHED),
                StructuredArguments.keyValue("statementId", r.statement().getId()),
                StructuredArguments.keyValue("fields", r.changedFields()),
                StructuredArguments.keyValue("rowsAdded", added),
                StructuredArguments.keyValue("extraRows", extra),
                StructuredArguments.keyValue("keptEdited", r.keptEdited()),
                StructuredArguments.keyValue("extraTransactionIds", r.extraTransactionIds()));
        String extraNote = extra == 0 ? null : extra + (extra == 1 ? " row" : " rows")
                + " already on this statement are not in the file (kept; check them)";
        String keptNote = r.keptEdited() == 0 ? null : r.keptEdited() + (r.keptEdited() == 1 ? " row" : " rows") + " kept (edited)";
        if (keptNote != null) {
            extraNote = extraNote == null ? keptNote : keptNote + "; " + extraNote;
        }
        if (r.changedFields().isEmpty() && added == 0) {
            String message = "Statement already ingested; nothing new to fill" + (extraNote == null ? "" : "; " + extraNote);
            return new FileIngestionResult.FileSummary(filename, "SKIPPED", 0, message, null, 0, 0, List.of());
        }
        List<String> parts = new ArrayList<>();
        if (!r.changedFields().isEmpty()) {
            parts.add("updated " + String.join(", ", r.changedFields().stream().map(f -> f.replace('_', ' ')).toList()));
        }
        if (added > 0) {
            parts.add("added " + added + (added == 1 ? " missing row" : " missing rows"));
        }
        if (extraNote != null) {
            parts.add(extraNote);
        }
        return new FileIngestionResult.FileSummary(filename, "SUCCESS", linesParsed, null,
                "Statement refreshed from this file: " + String.join("; ", parts), created, 0, r.changedFields());
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

