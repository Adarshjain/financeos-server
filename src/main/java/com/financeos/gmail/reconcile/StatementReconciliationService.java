package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
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
import com.financeos.gmail.ingest.GmailSender;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public StatementReconciliationService(GmailEngine gmailEngine,
                                          StatementParser statementParser,
                                          TransactionRepository transactionRepository,
                                          GmailProcessedMessageRepository processedMessageRepository,
                                          GmailIngestProperties ingestProperties) {
        this.gmailEngine = gmailEngine;
        this.statementParser = statementParser;
        this.transactionRepository = transactionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.ingestProperties = ingestProperties;
    }

    /**
     * Reconcile transactions from a bank statement email attachment.
     */
    @Transactional
    public ReconSummary reconcile(GmailConnection connection, GmailMessage message, GmailSender sender) {
        Account account = sender.getAccount();
        if (account == null) {
            log.error("No account bound to statement sender: {}", sender.getSenderAddress());
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "No account bound to statement sender");
            return new ReconSummary(0, 0, 1);
        }

        // 1. Find matching attachment
        GmailAttachment attachment = findStatementAttachment(message, sender);
        if (attachment == null) {
            log.warn("No attachment found in statement email: {}", message.messageId());
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "No statement attachment found");
            return new ReconSummary(0, 0, 1);
        }

        // 2. Fetch lazy attachment content
        byte[] attachmentBytes;
        try {
            attachmentBytes = gmailEngine.fetchAttachmentContent(connection, message.messageId(), attachment.attachmentId());
        } catch (Exception e) {
            log.error("Failed to fetch statement attachment content", e);
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "Failed to download attachment: " + e.getMessage());
            return new ReconSummary(0, 0, 1);
        }

        // 3. Decrypt and extract text content from PDF/Excel
        String text;
        try {
            String filename = attachment.filename().toLowerCase();
            if (filename.endsWith(".pdf")) {
                String password = getStatementPassword(account);
                text = statementParser.extractTextFromPdf(attachmentBytes, password);
            } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
                text = statementParser.extractTextFromExcel(attachmentBytes);
            } else {
                log.warn("Unsupported attachment type: {}", attachment.filename());
                recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "Unsupported file format: " + attachment.filename());
                return new ReconSummary(0, 0, 1);
            }
        } catch (Exception e) {
            log.error("Failed to extract text from attachment", e);
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "Failed to extract text: " + e.getMessage());
            return new ReconSummary(0, 0, 1);
        }

        // 4. Parse text using Gemini
        StatementExtractionResult result = statementParser.parse(text);
        if (!result.success()) {
            log.error("Gemini failed to parse statement: {}", result.failureReason());
            recordLedger(connection, message.messageId(), GmailProcessedStatus.FAILED, "Gemini parse failed: " + result.failureReason());
            return new ReconSummary(0, 0, 1);
        }

        List<ParsedStatementLine> allLines = result.lines();
        if (allLines.isEmpty()) {
            log.info("No transaction lines parsed from statement: {}", message.messageId());
            recordLedger(connection, message.messageId(), GmailProcessedStatus.RECONCILED, null);
            return new ReconSummary(0, 0, 0);
        }

        // 5. Watermark gate check: drop lines dated before the account watermark
        LocalDate watermark = account.getIngestFromDate();
        List<ParsedStatementLine> candidateLines = allLines.stream()
                .filter(line -> watermark == null || !line.date().isBefore(watermark))
                .toList();

        if (candidateLines.isEmpty()) {
            log.info("All statement lines were before watermark date: {}", watermark);
            recordLedger(connection, message.messageId(), GmailProcessedStatus.RECONCILED, null);
            return new ReconSummary(0, 0, 0);
        }

        // Find min and max dates of the lines to define query window
        LocalDate minLineDate = candidateLines.stream().map(ParsedStatementLine::date).min(LocalDate::compareTo).get();
        LocalDate maxLineDate = candidateLines.stream().map(ParsedStatementLine::date).max(LocalDate::compareTo).get();

        int dateWindow = ingestProperties.getDateWindowDays();
        LocalDate searchStart = minLineDate.minusDays(dateWindow);
        LocalDate searchEnd = maxLineDate.plusDays(dateWindow);

        // Fetch all transactions for this account in the window
        List<Transaction> allPeriodTxns = transactionRepository.findByAccountIdAndDateRange(account.getId(), searchStart, searchEnd);

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
        int matchedCount = 0;
        int createdCount = 0;

        // Loop over candidate statement lines and match/reconcile
        for (int i = 0; i < candidateLines.size(); i++) {
            ParsedStatementLine line = candidateLines.get(i);
            
            // Check if there is already a transaction matching this line (safety against seams)
            Transaction seamMatch = findBestMatch(line, alreadyMatchedTxns, dateWindow, consumedTxnIds);
            if (seamMatch != null) {
                // Already matching a manual/import/AUTO_REVIEWED transaction, skip creating or promoting (no-op)
                consumedTxnIds.add(seamMatch.getId());
                continue;
            }

            // Try to match against NEEDS_REVIEW alerts to promote
            Transaction alertMatch = findBestMatch(line, alertsToPromote, dateWindow, consumedTxnIds);
            if (alertMatch != null) {
                // Match found! Promote alert to AUTO_REVIEWED
                consumedTxnIds.add(alertMatch.getId());
                alertMatch.setReviewType(ReviewType.AUTO_REVIEWED);
                transactionRepository.save(alertMatch);
                matchedCount++;
            } else {
                // No match found -> materialize as a new gmail_statement transaction
                String sourceMsgId = String.format("%s:%d", message.messageId(), i);
                
                // Idempotency check: verify if this statement line is already persisted
                boolean alreadyCreated = transactionRepository.existsBySourceMessageId(sourceMsgId);
                if (!alreadyCreated) {
                    Transaction statementTxn = new Transaction();
                    statementTxn.setUser(connection.getUser());
                    statementTxn.setAccount(account);
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

    private GmailAttachment findStatementAttachment(GmailMessage message, GmailSender sender) {
        List<GmailAttachment> attachments = message.attachments();
        if (attachments.isEmpty()) {
            return null;
        }
        String pattern = sender.getAttachmentPattern();
        if (pattern != null && !pattern.isBlank()) {
            for (GmailAttachment att : attachments) {
                if (att.filename().matches(pattern) || att.filename().toLowerCase().contains(pattern.toLowerCase())) {
                    return att;
                }
            }
        }
        return attachments.get(0);
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
