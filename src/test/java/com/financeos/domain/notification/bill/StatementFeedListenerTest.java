package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.notification.StatementCreatedEvent;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementFeedListenerTest {

    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final NotificationFeedService feedService = mock(NotificationFeedService.class);
    private final StatementFeedListener listener = new StatementFeedListener(statementRepository, feedService);

    private final UUID userId = UUID.randomUUID();
    private Statement statement;
    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("HDFC Infinia");
        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setAccount(account);
        statement.setSource(StatementSource.gmail);
        statement.setStatementType("credit_card");
        statement.setPeriodEnd(LocalDate.of(2026, 10, 15));
        statement.setTransactionCount(42);
        StatementCreditCardDetails d = new StatementCreditCardDetails();
        d.setTotalAmountDue(new BigDecimal("36600.00"));
        d.setPaymentDueDate(LocalDate.of(2026, 11, 5));
        statement.setCreditCardDetails(d);
        when(statementRepository.findById(statement.getId())).thenReturn(Optional.of(statement));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void fire() {
        listener.onStatementCreated(new StatementCreatedEvent(statement.getId(), userId));
    }

    @Test
    void gmailCardStatementNamesTheBillAndTheTransactionCount() {
        fire();

        verify(feedService).record(userId, AppNotificationKind.STATEMENT_IMPORTED, "HDFC Infinia · Oct statement imported",
                "Bill ₹36,600 due 5 Nov · 42 transactions", AppNotificationRefs.ofAccount(account.getId()), "statement:" + statement.getId());
        assertNull(UserContext.getCurrentUserId());
    }

    @Test
    void fileUploadIsNeverRecorded() {
        statement.setSource(StatementSource.file_upload);

        fire();

        verify(feedService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonCardStatementHasOnlyTheTransactionCount() {
        statement.setStatementType("bank_account");
        statement.setCreditCardDetails(null);
        statement.setTransactionCount(1);

        fire();

        verify(feedService).record(eq(userId), eq(AppNotificationKind.STATEMENT_IMPORTED), eq("HDFC Infinia · Oct statement imported"),
                eq("1 transaction"), any(), any());
    }

    @Test
    void cardWithoutDueFiguresOrCountHasNoBody() {
        statement.getCreditCardDetails().setPaymentDueDate(null);
        statement.setTransactionCount(null);

        fire();

        verify(feedService).record(eq(userId), eq(AppNotificationKind.STATEMENT_IMPORTED), any(), eq((String) null), any(), any());
    }

    @Test
    void missingStatementIsIgnoredAndFeedFailureNeverThrows() {
        listener.onStatementCreated(new StatementCreatedEvent(UUID.randomUUID(), userId));
        verify(feedService, never()).record(any(), any(), any(), any(), any(), any());

        doThrow(new IllegalStateException("feed down")).when(feedService).record(any(), any(), any(), any(), any(), any());
        fire();
    }
}
