package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementPersistenceService.TxnLink;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.AccountResolver;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A row another statement already linked may only be re-matched when this statement also covers its date.
 * This statement's lines span 2 Oct – 20 Oct, so the search window (±3 days) reaches back to 29 Sep.
 */
class StatementReconciliationClaimedRowsTest {

    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCT_02 = LocalDate.of(2026, 10, 2);
    private static final LocalDate OCT_05 = LocalDate.of(2026, 10, 5);
    private static final LocalDate OCT_20 = LocalDate.of(2026, 10, 20);
    private static final String MESSAGE_ID = "msg-oct";

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final StatementPersistenceService statementPersistenceService = mock(StatementPersistenceService.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final AccountResolver accountResolver = mock(AccountResolver.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private GmailMessage message;
    private Account account;

    @BeforeEach
    void setUp() throws Exception {
        service = new StatementReconciliationService(gmailEngine, statementParser, transactionRepository,
                mock(GmailProcessedMessageRepository.class), new GmailIngestProperties(), mock(AccountRepository.class),
                accountResolver, new TransactionMatcher(), new ReviewStatusManager(), statementPersistenceService,
                mock(CardRepository.class));

        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(new User());
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));

        message = new GmailMessage(MESSAGE_ID, Instant.now(), "alerts@bank", "Statement", "", "", null,
                List.of(new GmailAttachment("att-1", "statement.pdf", "application/pdf", null)));
        byte[] pdf = blankPdf();
        when(gmailEngine.fetchAttachmentContent(connection, MESSAGE_ID, "att-1")).thenReturn(pdf);
        when(accountResolver.resolve("XXXX1234")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        Statement statement = new Statement();
        statement.setId(UUID.randomUUID());
        when(statementPersistenceService.createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), any()))
                .thenReturn(Optional.of(statement));
        when(statementParser.parse(pdf, null)).thenReturn(StatementExtractionResult.success(List.of(
                new ParsedStatementLine(OCT_02, new BigDecimal("-500.00"), "DEBIT", "UPI DR 498765432109 RAMESH KUMAR", null, null),
                new ParsedStatementLine(OCT_20, new BigDecimal("-90.00"), "DEBIT", "UPI DR 411111111111 TEA STALL", null, null)
        ), "XXXX1234", OCT_02, OCT_20, null));
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** An alert row (alert text, so only the loose amount/date rule applies to it). */
    private static Transaction alertRow(LocalDate date, ReviewType reviewType) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setSource(TransactionSource.gmail_transaction_alert);
        t.setReviewType(reviewType);
        t.setDate(date);
        t.setAmount(new BigDecimal("500.00"));
        t.setType(TransactionType.DEBIT);
        t.setSourcedDescription("Ramesh Kumar");
        if (reviewType == ReviewType.NEEDS_REVIEW) {
            t.setReviewReasons(new HashSet<>(Set.of(ReviewReason.UNRECONCILED, ReviewReason.CATEGORY_UNVERIFIED)));
        }
        return t;
    }

    private ReconSummary reconcileWith(Transaction existing, boolean claimedByAnotherStatement) {
        when(transactionRepository.findByAccountIdAndDateRange(account.getId(), SEP_30.minusDays(1), OCT_20.plusDays(3)))
                .thenReturn(List.of(existing));
        when(statementPersistenceService.findStatementLinkedTransactionIds(account.getId(), SEP_30.minusDays(1), OCT_20.plusDays(3)))
                .thenReturn(claimedByAnotherStatement ? Set.of(existing.getId()) : Set.of());
        return service.reconcile(connection, message);
    }

    @SuppressWarnings("unchecked")
    private List<TxnLink> linksWritten() {
        ArgumentCaptor<List<TxnLink>> captor = ArgumentCaptor.forClass(List.class);
        verify(statementPersistenceService).linkTransactions(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void reviewedRowClaimedByLastStatementIsNotReusedForAPaymentAcrossTheBoundary() {
        Transaction septemberPayment = alertRow(SEP_30, ReviewType.AUTO_REVIEWED);

        ReconSummary summary = reconcileWith(septemberPayment, true);

        assertThat(summary.created()).isEqualTo(2);
        assertThat(summary.createdTransactions()).extracting(Transaction::getDate).containsExactly(OCT_02, OCT_20);
        assertThat(linksWritten()).extracting(TxnLink::transactionId).doesNotContain(septemberPayment.getId());
    }

    @Test
    void pendingAlertClaimedByLastStatementIsNotPromotedForAPaymentAcrossTheBoundary() {
        Transaction septemberAlert = alertRow(SEP_30, ReviewType.NEEDS_REVIEW);

        ReconSummary summary = reconcileWith(septemberAlert, true);

        assertThat(summary.created()).isEqualTo(2);
        assertThat(summary.matched()).isZero();
        assertThat(septemberAlert.getReviewReasons()).contains(ReviewReason.UNRECONCILED);
    }

    @Test
    void unclaimedRowJustBeforeThisStatementStillMatchesWithinTheDateWindow() {
        Transaction earlierAlert = alertRow(SEP_30, ReviewType.AUTO_REVIEWED);

        ReconSummary summary = reconcileWith(earlierAlert, false);

        assertThat(summary.created()).isEqualTo(1);
        assertThat(linksWritten()).filteredOn(l -> l.lineIndex() == 0)
                .extracting(TxnLink::transactionId).containsExactly(earlierAlert.getId());
    }

    @Test
    void claimedRowInsideThisStatementsSpanStillMatchesForOverlappingStatements() {
        Transaction overlapRow = alertRow(OCT_05, ReviewType.AUTO_REVIEWED);

        ReconSummary summary = reconcileWith(overlapRow, true);

        assertThat(summary.created()).isEqualTo(1);
        assertThat(linksWritten()).filteredOn(l -> l.lineIndex() == 0)
                .extracting(TxnLink::transactionId).containsExactly(overlapRow.getId());
    }
}
