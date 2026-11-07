package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.ParkedMessageSettler;
import com.financeos.gmail.ingest.SyncSummary;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.match.StatementBatchMatcher;

@Service
public class StatementReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StatementReconciliationService.class);
    static final String NO_PERIOD_REASON = "No statement period found: not a statement (e.g. an EMI or other bank letter); not imported";

    private final GmailEngine gmailEngine;
    private final StatementParser statementParser;
    private final TransactionRepository transactionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final AccountRepository accountRepository;
    private final com.financeos.gmail.ingest.AccountResolver accountResolver;
    private final ReviewStatusManager reviewStatusManager;
    private final StatementPersistenceService statementPersistenceService;
    private final com.financeos.domain.account.card.CardRepository cardRepository;
    private final ParkedMessageSettler parkedMessageSettler;

    /** Optional so tests that build the service by hand need no publisher. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Set by Spring; tests that build the service by hand and never reach matching need none. */
    private StatementBatchMatcher batchMatcher;

    @org.springframework.beans.factory.annotation.Autowired
    public void setBatchMatcher(StatementBatchMatcher batchMatcher) {
        this.batchMatcher = batchMatcher;
    }

    /** Optional collaborators (set by Spring): tests that build the service by hand and do not need them leave them null. */
    private com.financeos.domain.statement.StatementCoverage statementCoverage;
    private com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader unlockProfileLoader;
    private com.financeos.domain.ingestion.StatementPasswordVault passwordVault;
    private final com.financeos.gmail.accountdetect.unlock.StatementUnlocker ruleUnlocker =
            com.financeos.gmail.accountdetect.unlock.StatementUnlocker.bundled();
    private StatementAccountGuess accountGuess;

    private com.financeos.domain.account.AccountIdentifierRepository identifierRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIdentifierRepository(com.financeos.domain.account.AccountIdentifierRepository identifierRepository) {
        this.identifierRepository = identifierRepository;
        this.accountGuess = new StatementAccountGuess(accountRepository, cardRepository, identifierRepository);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStatementCoverage(com.financeos.domain.statement.StatementCoverage statementCoverage) {
        this.statementCoverage = statementCoverage;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUnlockProfileLoader(com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader unlockProfileLoader) {
        this.unlockProfileLoader = unlockProfileLoader;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPasswordVault(com.financeos.domain.ingestion.StatementPasswordVault passwordVault) {
        this.passwordVault = passwordVault;
    }

    public StatementReconciliationService(GmailEngine gmailEngine,
                                          StatementParser statementParser,
                                          TransactionRepository transactionRepository,
                                          GmailProcessedMessageRepository processedMessageRepository,
                                          AccountRepository accountRepository,
                                          com.financeos.gmail.ingest.AccountResolver accountResolver,
                                          ReviewStatusManager reviewStatusManager,
                                          StatementPersistenceService statementPersistenceService,
                                          com.financeos.domain.account.card.CardRepository cardRepository,
                                          ParkedMessageSettler parkedMessageSettler) {
        this.gmailEngine = gmailEngine;
        this.statementParser = statementParser;
        this.transactionRepository = transactionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.accountRepository = accountRepository;
        this.accountResolver = accountResolver;
        this.reviewStatusManager = reviewStatusManager;
        this.statementPersistenceService = statementPersistenceService;
        this.cardRepository = cardRepository;
        this.parkedMessageSettler = parkedMessageSettler;
        this.accountGuess = new StatementAccountGuess(accountRepository, cardRepository, identifierRepository);
    }


    private record ChosenAttachment(GmailAttachment attachment, byte[] bytes) {}

    /**
     * Reconcile transactions from a bank statement email attachment.
     */
    @Transactional
    public ReconSummary reconcile(GmailConnection connection, GmailMessage message) {
        // 1. Pick statement attachment (PDF/XLSX, prefer password-protected); fetch bytes lazily
        ChosenAttachment chosen = pickStatementAttachment(connection, message);
        if (chosen == null) {
            log.warn("No statement attachment found in email: {}", message.messageId());
            recordLedger(connection, message.messageId(), null, GmailProcessedStatus.SKIPPED_NOT_TRANSACTION, "No statement attachment found");
            return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.SKIPPED_NOT_TRANSACTION, "No statement attachment found", null, null, false);
        }

        GmailAttachment chosenAttachment = chosen.attachment();
        byte[] chosenBytes = chosen.bytes();
        String filename = chosenAttachment.filename().toLowerCase();

        // 2. Identify the account by password-trial for a protected file
        boolean isProtected = false;
        if (filename.endsWith(".pdf")) {
            isProtected = isPdfEncrypted(chosenBytes);
        } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            isProtected = isExcelEncrypted(chosenBytes);
        }

        Account candidateAccount = null;
        String correctPassword = null;

        String ruleOpenedPassword = null;
        if (isProtected) {
            // The mail often names its account outside the PDF: one that is out of window or already covered needs no password.
            StatementAccountGuess.Guess guess = accountGuess.find(connection.getUser().getId(), message).orElse(null);
            if (guess != null) {
                ReconSummary skipped = skipWithoutOpening(connection, message, guess, chosenAttachment.filename());
                if (skipped != null) {
                    return skipped;
                }
            }
            List<Account> pwdAccounts = accountRepository.findByUserIdAndHasStatementPassword(connection.getUser().getId());
            PasswordMatch match = matchByPassword(pwdAccounts, chosenBytes, filename.endsWith(".pdf"));
            if (match != null) {
                candidateAccount = match.account();
                correctPassword = match.password();
            } else if (filename.endsWith(".pdf")) {
                // No saved password opens it: the bank's rules built from the user's name and birth date.
                ruleOpenedPassword = tryBankRules(connection, message, chosenBytes, guess);
                correctPassword = ruleOpenedPassword;
            }

            if (candidateAccount == null && ruleOpenedPassword == null) {
                return settleUndecryptable(connection, message, chosenAttachment.filename(), guess);
            }
        }

        // 3. Parse content with the statement parser
        StatementExtractionResult result = statementParser.parse(chosenBytes, correctPassword);
        if (!result.success()) {
            log.error("Statement parse failed: {}", result.failureReason());
            recordLedger(connection, message.messageId(), null, GmailProcessedStatus.FAILED_PERMANENT, "Statement parse failed: " + result.failureReason());
            return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.FAILED_PERMANENT, "Statement parse failed: " + result.failureReason(), chosenAttachment.filename(), null, false);
        }

        List<ParsedStatementLine> allLines = result.lines();
        if (allLines.isEmpty()) {
            log.info("No transaction lines parsed from statement: {}. Skipping as non-statement.", message.messageId());
            recordLedger(connection, message.messageId(), candidateAccount, GmailProcessedStatus.SKIPPED_NOT_TRANSACTION,
                    "No transaction lines parsed from statement; skipped as non-statement");
            return new ReconSummary(0, 0, 0, List.of(), null, null, chosenAttachment.filename(), null, true);
        }

        // A statement prints its period. A PDF with dated rows but no period is a bank letter (an EMI letter's repayment
        // schedule reads as rows): never imported as a statement.
        if (result.periodStart() == null || result.periodEnd() == null) {
            log.info("No statement period in {} ({} dated rows); skipped as not a statement", message.messageId(), allLines.size());
            recordLedger(connection, message.messageId(), candidateAccount, GmailProcessedStatus.SKIPPED_NOT_TRANSACTION, NO_PERIOD_REASON);
            return new ReconSummary(0, 0, 0, List.of(), null, null, chosenAttachment.filename(), null, true);
        }

        // 4. Confirm/resolve account
        Account resolvedAccount = null;
        String statementAccountNumber = result.accountNumber();

        // Resolve by statement's account number using exactly-one rule (via AccountResolver)
        com.financeos.gmail.ingest.AccountResolver.ResolvedCard last4Resolved = accountResolver.resolve(statementAccountNumber).orElse(null);
        Account last4ResolvedAccount = last4Resolved != null ? last4Resolved.account() : null;

        if (candidateAccount != null) {
            com.financeos.domain.account.card.Card matchedCandidateCard = findMatchingCardOnAccount(candidateAccount, statementAccountNumber);
            if (matchedCandidateCard != null) {
                resolvedAccount = candidateAccount;
            } else if (candidateAccount.getBankDetails() != null && statementNumberMatches(statementAccountNumber, candidateAccount.getBankDetails().getLast4())) {
                resolvedAccount = candidateAccount;
            } else {
                resolvedAccount = last4ResolvedAccount;
            }
        } else {
            resolvedAccount = last4ResolvedAccount;
        }

        String statementLast4 = (statementAccountNumber != null && statementAccountNumber.length() >= 4)
                ? statementAccountNumber.substring(statementAccountNumber.length() - 4) : statementAccountNumber;

        if (resolvedAccount == null) {
            log.error("Could not resolve account for statement (accountNumber: {}). Ingestion failed.", statementAccountNumber);
            recordLedger(connection, message.messageId(), null, GmailProcessedStatus.UNRESOLVED_ACCOUNT, statementLast4,
                    "Failed to resolve account for statement accountNumber: " + statementAccountNumber);
            return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.UNRESOLVED_ACCOUNT,
                    "No single account matches statement number " + statementAccountNumber, chosenAttachment.filename(), statementLast4, false);
        }

        if (ruleOpenedPassword != null && passwordVault != null
                && resolvedAccount.getBank() != null
                && resolvedAccount.getBank().equals(com.financeos.gmail.classify.BankSenderDomains.current().bankIdFor(message.from()))) {
            // The password came from a rule, not a saved one: keep it on the account the statement belongs to (encrypted at rest by the account).
            if (passwordVault.saveIfMissing(connection.getUser().getId(), resolvedAccount.getId(), ruleOpenedPassword)) {
                log.info("Saved the rule-built statement password on account {}", resolvedAccount.getId());
            }
        }

        if (resolvedAccount.getIngestFromDate() == null) {
            log.info("Resolved account {} for statement is not opted-in (ingestFromDate is null). Parking message.", resolvedAccount.getId());
            boolean closed = resolvedAccount.isClosed(); // a closed account with import off is skipped quietly
            recordLedger(connection, message.messageId(), resolvedAccount,
                    closed ? GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK : GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, statementLast4,
                    closed ? null : "Account " + resolvedAccount.getName() + " is not opted in for Gmail ingestion");
            return new ReconSummary(0, 0, 0, List.of(), null, "Account not opted in", chosenAttachment.filename(), statementLast4, false);
        }

        // 5. Create statement record if not a duplicate (before watermark filtering / matching)
        Optional<Statement> stmt = statementPersistenceService.createIfNew(connection.getUser(), resolvedAccount,
                StatementSource.gmail, message.messageId(), StatementPersistenceService.sha256Hex(chosenBytes), result.draft());
        if (stmt.isEmpty()) {
            log.info("Statement already ingested for account {} (message {})", resolvedAccount.getId(), message.messageId());
            updateLastStatementDate(resolvedAccount, result);
            recordLedger(connection, message.messageId(), resolvedAccount, GmailProcessedStatus.RECONCILED, null);
            return new ReconSummary(0, 0, 0);
        }

        // 6. Watermark gate check: drop lines dated before the account watermark
        LocalDate watermark = resolvedAccount.getIngestFromDate();
        List<ParsedStatementLine> candidateLines = allLines.stream()
                .filter(line -> watermark == null || !line.date().isBefore(watermark))
                .toList();

        if (candidateLines.isEmpty()) {
            log.info("All statement lines were before watermark date: {}", watermark);
            updateLastStatementDate(resolvedAccount, result);
            recordLedger(connection, message.messageId(), resolvedAccount, GmailProcessedStatus.RECONCILED, null);
            return new ReconSummary(0, 0, 0);
        }

        // Match the whole statement at once; lines nothing pairs with become gmail_statement rows (Confirmed).
        if (batchMatcher == null) {
            throw new IllegalStateException("StatementBatchMatcher is not available");
        }
        List<StatementBatchMatcher.Line> batchLines = new ArrayList<>();
        for (int i = 0; i < candidateLines.size(); i++) {
            batchLines.add(StatementBatchMatcher.Line.of(i, candidateLines.get(i)));
        }
        Account account = resolvedAccount;
        StatementBatchMatcher.Result matched = batchMatcher.match(stmt.get(), account, batchLines, unpaired -> {
            Map<Integer, Transaction> made = new HashMap<>();
            for (StatementBatchMatcher.Line batchLine : unpaired) {
                ParsedStatementLine line = candidateLines.get(batchLine.lineIndex());
                String sourceMsgId = String.format("%s:%d", message.messageId(), batchLine.lineIndex());
                if (transactionRepository.existsBySourceMessageId(sourceMsgId)) {
                    continue;
                }
                Transaction statementTxn = new Transaction();
                statementTxn.setUser(connection.getUser());
                statementTxn.setAccount(account);
                statementTxn.setCard(resolveCard(account, line.cardLast4()));
                statementTxn.setDate(line.date());
                statementTxn.setAmount(line.amount().abs());
                statementTxn.setSourcedDescription(line.description());
                statementTxn.setSource(TransactionSource.gmail_statement);
                statementTxn.setType(TransactionType.fromLlmDirection(line.direction()));
                reviewStatusManager.transitionTo(statementTxn, ReviewType.AUTO_REVIEWED);
                statementTxn.setSourceMessageId(sourceMsgId);
                statementTxn.setTransactionUnderMonitoring(false);
                statementTxn.setTransactionExcluded(false);
                transactionRepository.save(statementTxn);
                made.put(batchLine.lineIndex(), statementTxn);
            }
            return made;
        });
        int matchedCount = matched.matched();
        List<Transaction> createdTxns = matched.createdTransactions();
        int createdCount = createdTxns.size();

        // Record successful reconciliation run in the ledger
        updateLastStatementDate(resolvedAccount, result);
        recordLedger(connection, message.messageId(), resolvedAccount, GmailProcessedStatus.RECONCILED, null);

        // Categorization is not done here: the caller (GmailIngestionService) categorizes the
        // returned transactions after this transaction commits.
        if (createdCount > 0 && eventPublisher != null) {
            eventPublisher.publishEvent(com.financeos.domain.transaction.autolink.TransactionsIngestedEvent.of(connection.getUser().getId(), createdTxns));
        }
        return new ReconSummary(createdCount, matchedCount, 0, createdTxns);
    }

    private ChosenAttachment pickStatementAttachment(GmailConnection connection, GmailMessage message) {
        List<GmailAttachment> statementAtts = new ArrayList<>();
        for (GmailAttachment att : message.attachments()) {
            String filename = att.filename().toLowerCase();
            if (filename.endsWith(".pdf") || filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                statementAtts.add(att);
            }
        }
        if (statementAtts.isEmpty()) {
            return null;
        }
        if (statementAtts.size() == 1) {
            GmailAttachment att = statementAtts.get(0);
            byte[] bytes = gmailEngine.fetchAttachmentContent(connection, message.messageId(), att.attachmentId());
            return new ChosenAttachment(att, bytes);
        }

        // Among multiple, prefer a password-protected PDF
        for (GmailAttachment att : statementAtts) {
            if (att.filename().toLowerCase().endsWith(".pdf")) {
                try {
                    byte[] bytes = gmailEngine.fetchAttachmentContent(connection, message.messageId(), att.attachmentId());
                    if (isPdfEncrypted(bytes)) {
                        return new ChosenAttachment(att, bytes);
                    }
                } catch (Exception e) {
                    log.warn("Failed to check encryption status of PDF {}", att.filename(), e);
                }
            }
        }

        // None were password-protected PDFs, ties/none -> first
        GmailAttachment first = statementAtts.get(0);
        byte[] bytes = gmailEngine.fetchAttachmentContent(connection, message.messageId(), first.attachmentId());
        return new ChosenAttachment(first, bytes);
    }

    private boolean isPdfEncrypted(byte[] bytes) {
        try {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                return doc.isEncrypted();
            }
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isExcelEncrypted(byte[] bytes) {
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            try (Workbook workbook = WorkbookFactory.create(is)) {
                return false;
            }
        } catch (org.apache.poi.EncryptedDocumentException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }



    private com.financeos.domain.account.card.Card findMatchingCardOnAccount(Account account, String statementNumber) {
        if (account == null || statementNumber == null) {
            return null;
        }
        List<com.financeos.domain.account.card.Card> cards = cardRepository.findByAccountId(account.getId());
        for (com.financeos.domain.account.card.Card card : cards) {
            if (card.isOpen() && statementNumberMatches(statementNumber, card.getLast4())) {
                return card;
            }
        }
        return null;
    }

    private com.financeos.domain.account.card.Card resolveCard(Account account, String cardLast4) {
        if (account == null || cardLast4 == null) {
            return null;
        }
        String cleanLast4 = cardLast4.trim().replaceAll("\\s+", "");
        if (cleanLast4.length() < 4) {
            return null;
        }
        if (cleanLast4.length() > 4) {
            cleanLast4 = cleanLast4.substring(cleanLast4.length() - 4);
        }
        List<com.financeos.domain.account.card.Card> cards = cardRepository.findByAccountId(account.getId());
        for (com.financeos.domain.account.card.Card c : cards) {
            if (cleanLast4.equalsIgnoreCase(c.getLast4())) {
                return c;
            }
        }
        return null;
    }

    private boolean statementNumberMatches(String parsedNumber, String fragment) {
        if (parsedNumber == null || fragment == null) {
            return false;
        }
        String normalizedNumber = parsedNumber.trim().replaceAll("\\s+", "").toLowerCase();
        String normalizedFragment = fragment.trim().replaceAll("\\s+", "").toLowerCase();
        if (normalizedNumber.isEmpty() || normalizedFragment.isEmpty()) {
            return false;
        }
        return normalizedNumber.contains(normalizedFragment);
    }

    private static String getStatementPassword(Account account) {
        if (account.getCreditCardDetails() != null) {
            return account.getCreditCardDetails().getStatementPassword();
        }
        if (account.getBankDetails() != null) {
            return account.getBankDetails().getStatementPassword();
        }
        return null;
    }

    /**
     * A protected statement that no stored password opens. The bank usually still names the account
     * outside the PDF ("customer ID XXX1277", "XXX1277.pdf"); when that names exactly one account the
     * usual opt-in and window rules apply, so a statement from before the account's window is skipped
     * rather than asking for a password nobody needs. The digits are kept on the row either way, so a
     * row parked here is settled or reopened like any other when its account changes.
     */
    private ReconSummary settleUndecryptable(GmailConnection connection, GmailMessage message, String filename, StatementAccountGuess.Guess guess) {
        List<String> texts = new ArrayList<>(List.of(Objects.toString(message.subject(), ""), message.getStrippedText()));
        message.attachments().forEach(att -> texts.add(att.filename()));
        List<String> numbers = MaskedNumberHints.find(texts.toArray(String[]::new));
        Map<UUID, Account> accounts = new LinkedHashMap<>();
        Map<UUID, String> numberByAccount = new HashMap<>();
        // Same tie-breaks as alerts: a number shared by several accounts goes to the one at the sender's bank, or the single bank the mail names.
        List<String> mailTexts = List.of(Objects.toString(message.subject(), ""), Objects.toString(message.getStrippedText(), ""));
        for (String number : numbers) {
            accountResolver.resolve(number, mailTexts, message.from()).ifPresent(r -> {
                accounts.putIfAbsent(r.account().getId(), r.account());
                numberByAccount.putIfAbsent(r.account().getId(), number);
            });
        }

        if (accounts.size() == 1) {
            Account account = accounts.values().iterator().next();
            String last4 = numberByAccount.get(account.getId());
            if (account.getIngestFromDate() == null) {
                boolean closed = account.isClosed(); // a closed account with import off is skipped quietly
                recordLedger(connection, message.messageId(), account,
                        closed ? GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK : GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, last4,
                        closed ? null : "Account " + account.getName() + " is not opted in for Gmail ingestion");
                return new ReconSummary(0, 0, 0, List.of(), null, "Account not opted in", filename, last4, false);
            }
            if (message.internalDate() != null && message.internalDate().isBefore(parkedMessageSettler.windowStart(account.getIngestFromDate()))) {
                log.info("Undecryptable statement {} for account {} predates its ingest window; skipping", message.messageId(), account.getId());
                recordLedger(connection, message.messageId(), account, GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK, last4, null);
                return new ReconSummary(0, 0, 0, List.of(), null, null, filename, last4, false);
            }
            String reason = "Encrypted statement could not be decrypted. Save the statement password on " + account.getName() + ".";
            log.error("Encrypted statement for account {} could not be decrypted with any stored password. File: {}", account.getId(), filename);
            recordLedger(connection, message.messageId(), account, GmailProcessedStatus.FAILED_PERMANENT, last4, reason);
            return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.FAILED_PERMANENT, reason, filename, last4, false);
        }

        if (guess != null) {
            // The mail's own words named exactly one account (already judged in window): the password is missing there.
            Account account = guess.account();
            String reason = "Encrypted statement could not be decrypted. Save the statement password on " + account.getName() + ".";
            log.error("Encrypted statement for account {} could not be decrypted with any stored password. File: {}", account.getId(), filename);
            recordLedger(connection, message.messageId(), account, GmailProcessedStatus.FAILED_PERMANENT, guess.last4(), reason);
            return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.FAILED_PERMANENT, reason, filename, guess.last4(), false);
        }

        if (accounts.isEmpty() && numbers.size() == 1) {
            String last4 = numbers.get(0);
            log.error("Encrypted statement names account ending {} but no single account matches it", last4);
            recordLedger(connection, message.messageId(), null, GmailProcessedStatus.UNRESOLVED_ACCOUNT, last4,
                    "Failed to resolve account for encrypted statement ending " + last4);
            return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.UNRESOLVED_ACCOUNT,
                    "No single account matches statement number " + last4, filename, last4, false);
        }

        log.error("Encrypted statement could not be decrypted with any stored password. File: {}", filename);
        recordLedger(connection, message.messageId(), null, GmailProcessedStatus.FAILED_PERMANENT,
                "Encrypted statement could not be decrypted with any stored password.");
        return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.FAILED_PERMANENT, "Encrypted statement could not be decrypted with any stored password", filename, null, false);
    }

    /**
     * Judges a locked statement by the account its mail names, before any password is tried. Before the account's window (or
     * its closing, or with no ingest date) the mail is skipped like a watermark skip; a statement month the account's own
     * statements already cover in full is skipped as already held. Null when the statement is worth opening.
     */
    private ReconSummary skipWithoutOpening(GmailConnection connection, GmailMessage message, StatementAccountGuess.Guess guess, String filename) {
        Account account = guess.account();
        java.time.Instant sent = message.internalDate();
        // A skipped row must stay reactivatable by last4 (an ingest date moved back, an opt-in): no digits, no skip.
        if (sent == null || guess.last4() == null) {
            return null;
        }
        LocalDate sentDate = sent.atZone(com.financeos.core.time.AppTime.zone()).toLocalDate();
        // A final statement arrives shortly after closing: only a mail well after closing is skipped as closed.
        boolean closedLongBefore = account.getClosedOn() != null && sentDate.isAfter(account.getClosedOn().plusDays(CLOSED_GRACE_DAYS));
        if (closedLongBefore || account.getIngestFromDate() == null
                || sent.isBefore(parkedMessageSettler.windowStart(account.getIngestFromDate()))) {
            log.info("Locked statement {} for account {} is outside its ingest window; skipped without opening (matched by {})",
                    message.messageId(), account.getId(), guess.corroboration());
            recordLedger(connection, message.messageId(), account, GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK, guess.last4(), null);
            return new ReconSummary(0, 0, 0, List.of(), null, null, filename, guess.last4(), false);
        }
        if (statementCoverage != null && alreadyCovered(account, message, sentDate)) {
            log.info("Locked statement {} for account {} is already covered by a statement; skipped without opening", message.messageId(), account.getId());
            recordLedger(connection, message.messageId(), account, GmailProcessedStatus.SKIPPED_DUPLICATE, guess.last4(), null);
            return new ReconSummary(0, 0, 0, List.of(), null, null, filename, guess.last4(), false);
        }
        return null;
    }

    /** Days after an account's closing that a mail may still be its final statement. */
    static final int CLOSED_GRACE_DAYS = 45;

    /**
     * The calendar month before the mail was sent lies wholly inside the account's statements and so does the month the
     * subject names (when it names one). Without a month in the subject the sent day must be covered too.
     */
    private boolean alreadyCovered(Account account, GmailMessage message, LocalDate sentDate) {
        List<com.financeos.domain.statement.StatementCoverage.Period> periods = statementCoverage.statements(account.getId());
        if (periods.isEmpty()) {
            return false;
        }
        java.time.YearMonth before = java.time.YearMonth.from(sentDate).minusMonths(1);
        java.util.Optional<java.time.YearMonth> named = StatementAccountGuess.monthInSubject(message.subject());
        if (named.isPresent()) {
            return StatementAccountGuess.covers(periods, named.get()) && StatementAccountGuess.covers(periods, before);
        }
        return StatementAccountGuess.covers(periods, before) && StatementAccountGuess.covers(periods, sentDate, sentDate);
    }

    /**
     * Builds the bank's passwords from the user's name and birth date and returns the one that opens the PDF; null when none
     * does or the profile lacks either. The password is never logged.
     */
    private String tryBankRules(GmailConnection connection, GmailMessage message, byte[] pdf, StatementAccountGuess.Guess guess) {
        if (unlockProfileLoader == null) {
            return null;
        }
        try {
            com.financeos.gmail.accountdetect.unlock.UnlockProfile loaded = unlockProfileLoader.load(connection.getUser().getId());
            if (loaded.displayName() == null || loaded.displayName().isBlank() || loaded.birthDate() == null || loaded.birthDate().isBlank()) {
                return null;
            }
            // Saved passwords were already tried: the profile carries only the identity.
            com.financeos.gmail.accountdetect.unlock.UnlockProfile profile =
                    com.financeos.gmail.accountdetect.unlock.UnlockProfile.of(loaded.displayName(), loaded.birthDate());
            String bank = com.financeos.gmail.classify.BankSenderDomains.current().bankIdFor(message.from());
            if (bank == null) {
                return null;
            }
            com.financeos.gmail.accountdetect.Vocab.Kind kind = guess == null ? null
                    : guess.account().getType() == com.financeos.domain.account.AccountType.credit_card
                    ? com.financeos.gmail.accountdetect.Vocab.Kind.CREDIT_CARD
                    : com.financeos.gmail.accountdetect.Vocab.Kind.ACCOUNT;
            List<String> numbers = MaskedNumberHints.find(message.subject(), message.getStrippedText()).stream()
                    .filter(n -> n.length() == 4).toList();
            return ruleUnlocker.openingPassword(pdf,
                    new com.financeos.gmail.accountdetect.unlock.StatementUnlocker.Target(bank, kind, List.of(), numbers, null), profile);
        } catch (RuntimeException e) {
            log.warn("Bank password rules failed for message {}: {}", message.messageId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private void recordLedger(GmailConnection connection, String messageId, Account account, GmailProcessedStatus status, String extractedLast4, String error) {
        GmailProcessedMessage processed = processedMessageRepository
                .findByConnectionIdAndGmailMessageId(connection.getId(), messageId)
                .orElseGet(() -> {
                    GmailProcessedMessage pm = new GmailProcessedMessage();
                    pm.setConnection(connection);
                    pm.setUser(connection.getUser());
                    pm.setGmailMessageId(messageId);
                    return pm;
                });
        processed.setAccount(account);
        processed.setStatus(status);
        if (status == GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN) {
            processed.setArchivedAt(Instant.now()); // shown as one entry per account, not one row per email
        }
        processed.setExtractedLast4(extractedLast4);
        processed.setError(error);
        processed.setProcessedAt(Instant.now());
        processedMessageRepository.save(processed);
    }

    private void recordLedger(GmailConnection connection, String messageId, Account account, GmailProcessedStatus status, String error) {
        recordLedger(connection, messageId, account, status, null, error);
    }

    /** The account whose saved password opens the file, with the form of it that opened. */
    record PasswordMatch(Account account, String password) {}

    /**
     * The password that opens the file picks the account: every account's password as saved is tried before any case
     * variant (lowercase, then uppercase), so a variant never wins over another account's exact password. Null when none opens.
     */
    static PasswordMatch matchByPassword(List<Account> accounts, byte[] bytes, boolean pdf) {
        for (boolean variantsPass : new boolean[]{false, true}) {
            for (Account acc : accounts) {
                String pwd = getStatementPassword(acc);
                if (pwd == null || pwd.trim().isEmpty()) {
                    continue;
                }
                List<String> tries = com.financeos.statement.parser.PdfPasswords.variants(pwd);
                tries = variantsPass ? tries.subList(1, tries.size()) : tries.subList(0, 1);
                for (String variant : tries) {
                    if (opens(bytes, variant, pdf)) {
                        return new PasswordMatch(acc, variant);
                    }
                }
            }
        }
        return null;
    }

    /** True when this password opens the PDF (pdf) or the encrypted Excel file (otherwise). */
    private static boolean opens(byte[] bytes, String password, boolean pdf) {
        if (pdf) {
            try (PDDocument doc = Loader.loadPDF(bytes, password)) {
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        try (InputStream is = new ByteArrayInputStream(bytes);
             Workbook wb = WorkbookFactory.create(is, password)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateLastStatementDate(Account account, StatementExtractionResult result) {
        if (account == null) {
            return;
        }
        LocalDate effectiveEnd = result.periodEnd();
        if (effectiveEnd == null) {
            log.warn("Statement period end missing or invalid; falling back to max line date");
            effectiveEnd = result.lines().stream()
                    .map(ParsedStatementLine::date)
                    .max(LocalDate::compareTo)
                    .orElse(null);
        }
        if (effectiveEnd != null) {
            LocalDate existing = account.getLastStatementDate();
            if (existing == null || effectiveEnd.isAfter(existing)) {
                account.setLastStatementDate(effectiveEnd);
                accountRepository.save(account);
            }
        }
    }
}
