package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.transaction.*;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** An alert that arrives after its statement pairs with the statement row instead of creating a second transaction. */
class GmailTransactionWriterStatementPairTest {

    private TransactionRepository transactionRepository;
    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailTransactionWriter writer;
    private GmailConnection connection;
    private Account account;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        writer = new GmailTransactionWriter(transactionRepository, processedMessageRepository, new ReviewStatusManager());
        User user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        account = new Account();
        account.setId(UUID.randomUUID());
        when(processedMessageRepository.findByConnectionIdAndGmailMessageId(any(), any())).thenReturn(Optional.empty());
        when(processedMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static GeminiExtractionResult alert(LocalDate date) {
        return new GeminiExtractionResult(true, new BigDecimal("450.00"), "INR", "DEBIT", date, "SWIGGY", "7106", 0.9, false, null);
    }

    private static Transaction statementRow(LocalDate date) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setSource(TransactionSource.gmail_statement);
        t.setDate(date);
        t.setAmount(new BigDecimal("450.00"));
        t.setType(TransactionType.DEBIT);
        return t;
    }

    private void statementRowsAre(List<Transaction> rows) {
        when(transactionRepository.findUnpairedStatementRows(eq(account.getId()), eq(TransactionType.DEBIT),
                eq(new BigDecimal("450.00")), eq(LocalDate.of(2026, 8, 11)), eq(LocalDate.of(2026, 8, 13)), any()))
                .thenReturn(rows);
    }

    private static <T> T eq(T v) { return org.mockito.ArgumentMatchers.eq(v); }

    @Test
    void alertAfterItsStatementCreatesNoTransactionAndIsRecordedAsDuplicate() {
        Transaction stmt = statementRow(LocalDate.of(2026, 8, 13));
        statementRowsAre(List.of(stmt));

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", alert(LocalDate.of(2026, 8, 12)), account);

        assertEquals(GmailProcessedStatus.SKIPPED_DUPLICATE, result.getStatus());
        assertSame(stmt, result.getTransaction());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void closestStatementRowIsChosen() {
        Transaction far = statementRow(LocalDate.of(2026, 8, 13));
        Transaction near = statementRow(LocalDate.of(2026, 8, 12));
        statementRowsAre(List.of(far, near));

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", alert(LocalDate.of(2026, 8, 12)), account);

        assertSame(near, result.getTransaction());
    }

    @Test
    void alertWithNoStatementRowIsCreatedAsBefore() {
        statementRowsAre(List.of());

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", alert(LocalDate.of(2026, 8, 12)), account);

        assertEquals(GmailProcessedStatus.CREATED, result.getStatus());
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void statementRowAlreadyPairedWithAnotherAlertIsNotOfferedSoAlertIsCreated() {
        // The repository query leaves out rows a processed mail already stands for, so it returns nothing.
        statementRowsAre(List.of());

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-2", alert(LocalDate.of(2026, 8, 12)), account);

        assertEquals(GmailProcessedStatus.CREATED, result.getStatus());
    }

    @Test
    void differentMerchantOnTheStatementRowStillCreatesTheAlert() {
        Transaction stmt = statementRow(LocalDate.of(2026, 8, 12));
        stmt.setSourcedDescription("UPI/DR/4123/ZOMATO/YESB");
        statementRowsAre(List.of(stmt));

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", alert(LocalDate.of(2026, 8, 12)), account);

        assertEquals(GmailProcessedStatus.CREATED, result.getStatus());
    }

    @Test
    void sameMerchantOnTheStatementRowSkipsTheAlert() {
        Transaction stmt = statementRow(LocalDate.of(2026, 8, 12));
        stmt.setSourcedDescription("UPI/DR/4123/SWIGGY/YESB");
        statementRowsAre(List.of(stmt));

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", alert(LocalDate.of(2026, 8, 12)), account);

        assertEquals(GmailProcessedStatus.SKIPPED_DUPLICATE, result.getStatus());
        assertSame(stmt, result.getTransaction());
    }

    @Test
    void statementRowInAnUncertainDuplicatePairingIsNotACandidate() {
        Transaction stmt = statementRow(LocalDate.of(2026, 8, 12));
        stmt.setReviewReasons(new java.util.HashSet<>(java.util.Set.of(ReviewReason.DUPLICATE_SUSPECT)));
        statementRowsAre(List.of(stmt));

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", alert(LocalDate.of(2026, 8, 12)), account);

        assertEquals(GmailProcessedStatus.CREATED, result.getStatus());
    }
}
