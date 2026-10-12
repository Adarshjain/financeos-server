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
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A capped email-alert date is written as the transaction date and flagged DATE_CORRECTED. */
class GmailTransactionWriterDateCorrectedTest {

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
        account.setIngestFromDate(LocalDate.of(2026, 9, 17));

        when(processedMessageRepository.findByConnectionIdAndGmailMessageId(any(), any())).thenReturn(Optional.empty());
        when(processedMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static GeminiExtractionResult extracted(LocalDate date) {
        return new GeminiExtractionResult(true, new BigDecimal("209.70"), "INR", "DEBIT", date,
                "MARITIME DATABASE SERV", "7106", 0.9, true, null);
    }

    private Transaction written() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void correctedDateIsStoredAndFlagged() {
        GeminiExtractionResult capped = extracted(LocalDate.of(2026, 9, 26)).withDateCappedTo(LocalDate.of(2026, 9, 21));

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", capped, account);

        Transaction txn = written();
        assertEquals(GmailProcessedStatus.CREATED, result.getStatus());
        assertEquals(LocalDate.of(2026, 9, 21), txn.getDate());
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertEquals(Set.of(ReviewReason.UNRECONCILED, ReviewReason.DATE_CORRECTED), txn.getReviewReasons());
    }

    @Test
    void uncorrectedDateIsNotFlagged() {
        writer.writeTransaction(connection, "msg-1", extracted(LocalDate.of(2026, 9, 21)), account);

        Transaction txn = written();
        assertEquals(LocalDate.of(2026, 9, 21), txn.getDate());
        assertEquals(Set.of(ReviewReason.UNRECONCILED), txn.getReviewReasons());
    }

    @Test
    void watermarkJudgesTheCorrectedDate() {
        // The model's later date would pass the account's start date; the real (sent) day does not.
        account.setIngestFromDate(LocalDate.of(2026, 9, 22));
        GeminiExtractionResult capped = extracted(LocalDate.of(2026, 9, 26)).withDateCappedTo(LocalDate.of(2026, 9, 21));

        GmailProcessedMessage result = writer.writeTransaction(connection, "msg-1", capped, account);

        assertEquals(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK, result.getStatus());
        verify(transactionRepository, never()).save(any());
    }
}
