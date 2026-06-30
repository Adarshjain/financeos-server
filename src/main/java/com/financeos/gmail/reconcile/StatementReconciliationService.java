package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
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
import java.util.stream.Collectors;

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

    public StatementReconciliationService(GmailEngine gmailEngine,
                                          StatementParser statementParser,
                                          TransactionRepository transactionRepository,
                                          GmailProcessedMessageRepository processedMessageRepository,
                                          GmailIngestProperties ingestProperties,
                                          AccountRepository accountRepository,
                                          com.financeos.gmail.ingest.AccountResolver accountResolver) {
        this.gmailEngine = gmailEngine;
        this.statementParser = statementParser;
        this.transactionRepository = transactionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.ingestProperties = ingestProperties;
        this.accountRepository = accountRepository;
        this.accountResolver = accountResolver;
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
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "No statement attachment found");
            return new ReconSummary(0, 0, 1);
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
                recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED,
                        "Encrypted statement could not be decrypted with any stored password.");
                return new ReconSummary(0, 0, 1);
            }
        }

        // 3. Extract text content
        String text;
        try {
            if (filename.endsWith(".pdf")) {
                text = statementParser.extractTextFromPdf(chosenBytes, correctPassword);
            } else {
                text = statementParser.extractTextFromExcel(chosenBytes, correctPassword);
            }
        } catch (Exception e) {
            log.error("Failed to extract text from attachment", e);
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "Failed to extract text: " + e.getMessage());
            return new ReconSummary(0, 0, 1);
        }

        // 4. Parse content with Gemini statement model
        StatementExtractionResult result = statementParser.parse(text);
        if (!result.success()) {
            log.error("Gemini failed to parse statement: {}", result.failureReason());
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "Gemini parse failed: " + result.failureReason());
            return new ReconSummary(0, 0, 1);
        }

        List<ParsedStatementLine> allLines = result.lines();
        if (allLines.isEmpty()) {
            log.info("No transaction lines parsed from statement: {}. Skipping as non-statement.", message.messageId());
            recordLedger(connection, message.messageId(), GmailProcessedStatus.SKIPPED_NOT_TRANSACTION,
                    "No transaction lines parsed from statement; skipped as non-statement");
            return new ReconSummary(0, 0, 0);
        }

        // 5. Confirm/resolve account
        Account resolvedAccount = null;
        String statementLast4 = result.accountLast4();

        // Resolve by statement's last-4 using exactly-one rule (via AccountResolver)
        Account last4ResolvedAccount = accountResolver.resolve(statementLast4).orElse(null);


        if (candidateAccount != null) {
            String candidateLast4 = null;
            if (candidateAccount.getBankDetails() != null) {
                candidateLast4 = candidateAccount.getBankDetails().getLast4();
            } else if (candidateAccount.getCreditCardDetails() != null) {
                candidateLast4 = candidateAccount.getCreditCardDetails().getLast4();
            }

            if (candidateLast4 != null && statementLast4 != null
                    && candidateLast4.trim().equalsIgnoreCase(statementLast4.trim())) {
                resolvedAccount = candidateAccount;
            } else {
                resolvedAccount = last4ResolvedAccount;
            }
        } else {
            resolvedAccount = last4ResolvedAccount;
        }

        // 6. Watermark gate check: drop lines dated before the account watermark (if account resolved)
        LocalDate watermark = resolvedAccount != null ? resolvedAccount.getIngestFromDate() : null;
        List<ParsedStatementLine> candidateLines = allLines.stream()
                .filter(line -> watermark == null || !line.date().isBefore(watermark))
                .toList();

        if (candidateLines.isEmpty()) {
            log.info("All statement lines were before watermark date: {}", watermark);
            recordLedger(connection, message.messageId(), GmailProcessedStatus.RECONCILED, null);
            return new ReconSummary(0, 0, 0);
        }

        int matchedCount = 0;
        int createdCount = 0;

        if (resolvedAccount != null) {
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

                // Check if there is already a transaction matching this line (safety against seams)
                Transaction seamMatch = findBestMatch(line, alreadyMatchedTxns, dateWindow, consumedTxnIds);
                if (seamMatch != null) {
                    consumedTxnIds.add(seamMatch.getId());
                    continue;
                }

                // Try to match against NEEDS_REVIEW alerts to promote
                Transaction alertMatch = findBestMatch(line, alertsToPromote, dateWindow, consumedTxnIds);
                if (alertMatch != null) {
                    consumedTxnIds.add(alertMatch.getId());
                    alertMatch.setReviewType(ReviewType.AUTO_REVIEWED);
                    transactionRepository.save(alertMatch);
                    matchedCount++;
                } else {
                    // No match found -> materialize as a new gmail_statement transaction
                    String sourceMsgId = String.format("%s:%d", message.messageId(), i);
                    boolean alreadyCreated = transactionRepository.existsBySourceMessageId(sourceMsgId);
                    if (!alreadyCreated) {
                        Transaction statementTxn = new Transaction();
                        statementTxn.setUser(connection.getUser());
                        statementTxn.setAccount(resolvedAccount);
                        statementTxn.setDate(line.date());
                        statementTxn.setAmount(line.amount().abs());
                        statementTxn.setDescription(line.description());
                        statementTxn.setSource(TransactionSource.gmail_statement);
                        statementTxn.setType(TransactionType.valueOf(line.direction().toUpperCase()));
                        statementTxn.setReviewType(ReviewType.NEEDS_REVIEW);
                        statementTxn.setSourceMessageId(sourceMsgId);
                        statementTxn.setTransactionUnderMonitoring(false);
                        statementTxn.setTransactionExcluded(false);

                        transactionRepository.save(statementTxn);
                        createdCount++;
                    }
                }
            }
        } else {
            // account is null, we can't run matching or promote alerts, just write gmail_statement lines with account=null
            for (int i = 0; i < candidateLines.size(); i++) {
                ParsedStatementLine line = candidateLines.get(i);
                String sourceMsgId = String.format("%s:%d", message.messageId(), i);
                boolean alreadyCreated = transactionRepository.existsBySourceMessageId(sourceMsgId);
                if (!alreadyCreated) {
                    Transaction statementTxn = new Transaction();
                    statementTxn.setUser(connection.getUser());
                    statementTxn.setAccount(null);
                    statementTxn.setDate(line.date());
                    statementTxn.setAmount(line.amount().abs());
                    statementTxn.setDescription(line.description());
                    statementTxn.setSource(TransactionSource.gmail_statement);
                    statementTxn.setType(TransactionType.valueOf(line.direction().toUpperCase()));
                    statementTxn.setReviewType(ReviewType.NEEDS_REVIEW);
                    statementTxn.setSourceMessageId(sourceMsgId);
                    statementTxn.setTransactionUnderMonitoring(false);
                    statementTxn.setTransactionExcluded(false);

                    transactionRepository.save(statementTxn);
                    createdCount++;
                }
            }
        }

        // Record successful reconciliation run in the ledger
        recordLedger(connection, message.messageId(), GmailProcessedStatus.RECONCILED, null);

        return new ReconSummary(createdCount, matchedCount, 0);
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

    private Transaction findBestMatch(
            ParsedStatementLine line,
            List<Transaction> candidates,
            int dateWindow,
            Set<UUID> consumedTxnIds) {

        Transaction bestMatch = null;
        long bestDateDiff = Long.MAX_VALUE;
        double bestSimilarity = -1.0;

        for (Transaction candidate : candidates) {
            if (consumedTxnIds.contains(candidate.getId())) {
                continue;
            }

            // Exact amount and same direction check
            if (candidate.getAmount().compareTo(line.amount().abs()) != 0) {
                continue;
            }
            if (!candidate.getType().name().equalsIgnoreCase(line.direction())) {
                continue;
            }

            // Date within window check
            long dateDiff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(line.date(), candidate.getDate()));
            if (dateDiff > dateWindow) {
                continue;
            }

            // Find best using greedy metric (closest date, then description similarity)
            if (dateDiff < bestDateDiff) {
                bestMatch = candidate;
                bestDateDiff = dateDiff;
                bestSimilarity = calculateSimilarity(line.description(), candidate.getDescription());
            } else if (dateDiff == bestDateDiff) {
                double similarity = calculateSimilarity(line.description(), candidate.getDescription());
                if (similarity > bestSimilarity) {
                    bestMatch = candidate;
                    bestSimilarity = similarity;
                }
            }
        }

        return bestMatch;
    }

    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String clean1 = s1.toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        String clean2 = s2.toLowerCase().replaceAll("[^a-z0-9\\s]", "");
        String[] tokens1 = clean1.split("\\s+");
        String[] tokens2 = clean2.split("\\s+");

        Set<String> set1 = Arrays.stream(tokens1).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
        Set<String> set2 = Arrays.stream(tokens2).filter(t -> !t.isEmpty()).collect(Collectors.toSet());

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) return 0.0;
        return (double) intersection.size() / union.size();
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

    private void recordLedger(GmailConnection connection, String messageId, GmailProcessedStatus status, String error) {
        GmailProcessedMessage processed = processedMessageRepository
                .findByConnectionIdAndGmailMessageId(connection.getId(), messageId)
                .orElseGet(() -> {
                    GmailProcessedMessage pm = new GmailProcessedMessage();
                    pm.setConnection(connection);
                    pm.setUser(connection.getUser());
                    pm.setGmailMessageId(messageId);
                    return pm;
                });
        processed.setStatus(status);
        processed.setError(error);
        processed.setProcessedAt(Instant.now());
        processedMessageRepository.save(processed);
    }
}
