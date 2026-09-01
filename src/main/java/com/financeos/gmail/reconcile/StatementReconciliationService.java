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
import com.financeos.gmail.ingest.GmailIngestProperties;
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

import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewReason;

@Service
public class StatementReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StatementReconciliationService.class);

    private final GmailEngine gmailEngine;
    private final StatementParser statementParser;
    private final TransactionRepository transactionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final GmailIngestProperties ingestProperties;
    private final AccountRepository accountRepository;
    private final com.financeos.gmail.ingest.AccountResolver accountResolver;
    private final TransactionMatcher transactionMatcher;
    private final ReviewStatusManager reviewStatusManager;
    private final StatementPersistenceService statementPersistenceService;
    private final com.financeos.domain.account.card.CardRepository cardRepository;

    public StatementReconciliationService(GmailEngine gmailEngine,
                                          StatementParser statementParser,
                                          TransactionRepository transactionRepository,
                                          GmailProcessedMessageRepository processedMessageRepository,
                                          GmailIngestProperties ingestProperties,
                                          AccountRepository accountRepository,
                                          com.financeos.gmail.ingest.AccountResolver accountResolver,
                                          TransactionMatcher transactionMatcher,
                                          ReviewStatusManager reviewStatusManager,
                                          StatementPersistenceService statementPersistenceService,
                                          com.financeos.domain.account.card.CardRepository cardRepository) {
        this.gmailEngine = gmailEngine;
        this.statementParser = statementParser;
        this.transactionRepository = transactionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.ingestProperties = ingestProperties;
        this.accountRepository = accountRepository;
        this.accountResolver = accountResolver;
        this.transactionMatcher = transactionMatcher;
        this.reviewStatusManager = reviewStatusManager;
        this.statementPersistenceService = statementPersistenceService;
        this.cardRepository = cardRepository;
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

        if (isProtected) {
            List<Account> pwdAccounts = accountRepository.findByUserIdAndHasStatementPassword(connection.getUser().getId());
            for (Account acc : pwdAccounts) {
                String pwd = getStatementPassword(acc);
                if (pwd == null || pwd.trim().isEmpty()) {
                    continue;
                }
                if (filename.endsWith(".pdf")) {
                    try {
                        try (PDDocument doc = Loader.loadPDF(chosenBytes, pwd)) {
                            candidateAccount = acc;
                            correctPassword = pwd;
                            break;
                        }
                    } catch (IOException e) {
                        // try next password
                    }
                } else {
                    try (InputStream is = new ByteArrayInputStream(chosenBytes);
                         Workbook wb = WorkbookFactory.create(is, pwd)) {
                        candidateAccount = acc;
                        correctPassword = pwd;
                        break;
                    } catch (Exception e) {
                        // try next password
                    }
                }
            }

            if (candidateAccount == null) {
                log.error("Encrypted statement could not be decrypted with any stored password. File: {}", chosenAttachment.filename());
                recordLedger(connection, message.messageId(), null, GmailProcessedStatus.FAILED_PERMANENT,
                        "Encrypted statement could not be decrypted with any stored password.");
                return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.FAILED_PERMANENT, "Encrypted statement could not be decrypted with any stored password", chosenAttachment.filename(), null, false);
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

        if (resolvedAccount.getIngestFromDate() == null) {
            log.info("Resolved account {} for statement is not opted-in (ingestFromDate is null). Parking message.", resolvedAccount.getId());
            recordLedger(connection, message.messageId(), resolvedAccount, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, statementLast4,
                    "Account " + resolvedAccount.getName() + " is not opted in for Gmail ingestion");
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

        int matchedCount = 0;
        int createdCount = 0;
        List<Transaction> createdTxns = new ArrayList<>();
        List<StatementPersistenceService.TxnLink> links = new ArrayList<>();

        // Find min and max dates of the lines to define query window
        LocalDate minLineDate = candidateLines.stream().map(ParsedStatementLine::date).min(LocalDate::compareTo).get();
        LocalDate maxLineDate = candidateLines.stream().map(ParsedStatementLine::date).max(LocalDate::compareTo).get();

        int dateWindow = ingestProperties.getDateWindowDays();
        LocalDate searchStart = minLineDate.minusDays(dateWindow);
        LocalDate searchEnd = maxLineDate.plusDays(dateWindow);

        // Fetch all transactions for this account in the window
        List<Transaction> allPeriodTxns = transactionRepository.findByAccountIdAndDateRange(resolvedAccount.getId(), searchStart, searchEnd);

        // Separate NEEDS_REVIEW alerts from other transactions (AUTO_REVIEWED, manual, gmail_statement, etc.)
        List<Transaction> alertsToPromote = new ArrayList<>();
        List<Transaction> alreadyMatchedTxns = new ArrayList<>();

        for (Transaction t : allPeriodTxns) {
            if (t.getSource() == TransactionSource.gmail_transaction_alert && t.getReviewType() == ReviewType.NEEDS_REVIEW) {
                alertsToPromote.add(t);
            } else {
                alreadyMatchedTxns.add(t);
            }
        }

        Set<UUID> consumedTxnIds = new HashSet<>();

        // Loop over candidate statement lines and match/reconcile
        for (int i = 0; i < candidateLines.size(); i++) {
            ParsedStatementLine line = candidateLines.get(i);
            com.financeos.domain.account.card.Card lineCard = resolveCard(resolvedAccount, line.cardLast4());
            UUID lineCardId = lineCard != null ? lineCard.getId() : null;

            // Check if there is already a transaction matching this line (safety against seams)
            Transaction seamMatch = transactionMatcher.findBestMatch(line, lineCardId, alreadyMatchedTxns, dateWindow, consumedTxnIds);
            if (seamMatch != null) {
                consumedTxnIds.add(seamMatch.getId());
                links.add(new StatementPersistenceService.TxnLink(seamMatch.getId(), i, line.balance(), line.chainValid()));
                continue;
            }

            // Try to match against NEEDS_REVIEW alerts to promote
            Transaction alertMatch = transactionMatcher.findBestMatch(line, lineCardId, alertsToPromote, dateWindow, consumedTxnIds);
            if (alertMatch != null) {
                consumedTxnIds.add(alertMatch.getId());
                reviewStatusManager.clearReason(alertMatch, ReviewReason.UNRECONCILED, ReviewType.AUTO_REVIEWED);
                transactionRepository.save(alertMatch);
                matchedCount++;
                links.add(new StatementPersistenceService.TxnLink(alertMatch.getId(), i, line.balance(), line.chainValid()));
            } else {
                // No match found -> materialize as a new gmail_statement transaction
                String sourceMsgId = String.format("%s:%d", message.messageId(), i);
                boolean alreadyCreated = transactionRepository.existsBySourceMessageId(sourceMsgId);
                if (!alreadyCreated) {
                    Transaction statementTxn = new Transaction();
                    statementTxn.setUser(connection.getUser());
                    statementTxn.setAccount(resolvedAccount);
                    statementTxn.setCard(lineCard);
                    statementTxn.setDate(line.date());
                    statementTxn.setAmount(line.amount().abs());
                    statementTxn.setSourcedDescription(line.description());
                    statementTxn.setSource(TransactionSource.gmail_statement);
                    statementTxn.setType(TransactionType.fromLlmDirection(line.direction()));
                    reviewStatusManager.addReason(statementTxn, ReviewReason.UNRECONCILED);
                    statementTxn.setSourceMessageId(sourceMsgId);
                    statementTxn.setTransactionUnderMonitoring(false);
                    statementTxn.setTransactionExcluded(false);

                    transactionRepository.save(statementTxn);
                    createdCount++;
                    createdTxns.add(statementTxn);
                    links.add(new StatementPersistenceService.TxnLink(statementTxn.getId(), i, line.balance(), line.chainValid()));
                }
            }
        }

        if (stmt.isPresent()) {
            statementPersistenceService.linkTransactions(stmt.get().getId(), links);
        }

        // Record successful reconciliation run in the ledger
        updateLastStatementDate(resolvedAccount, result);
        recordLedger(connection, message.messageId(), resolvedAccount, GmailProcessedStatus.RECONCILED, null);

        // Categorization is deliberately NOT done here: this method is @Transactional and the
        // categorizer makes a Gemini HTTP call, which must not run while holding a DB connection.
        // The caller (GmailIngestionService) categorizes the returned transactions outside the tx.
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
        if (account == null || account.getType() != com.financeos.domain.account.AccountType.credit_card || statementNumber == null) {
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
        if (account == null || account.getType() != com.financeos.domain.account.AccountType.credit_card || cardLast4 == null) {
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

    private String getStatementPassword(Account account) {
        if (account.getCreditCardDetails() != null) {
            return account.getCreditCardDetails().getStatementPassword();
        }
        if (account.getBankDetails() != null) {
            return account.getBankDetails().getStatementPassword();
        }
        return null;
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
        processed.setExtractedLast4(extractedLast4);
        processed.setError(error);
        processed.setProcessedAt(Instant.now());
        processedMessageRepository.save(processed);
    }

    private void recordLedger(GmailConnection connection, String messageId, Account account, GmailProcessedStatus status, String error) {
        recordLedger(connection, messageId, account, status, null, error);
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
