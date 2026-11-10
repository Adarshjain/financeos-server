package com.financeos.domain.statement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.observability.AuditLogger;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.inbox.InboxItemStateRepository;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.statement.file.StatementFileStore;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionService;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Deleting a statement removes its stored original file, unless another statement still references it. */
class StatementDeletionFileTest {

    private StatementRepository statementRepository;
    private StatementFileStore fileStore;
    private StatementDeletionService service;
    private UUID userId;
    private Account account;
    private Statement statement;

    @BeforeEach
    void setUp() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        StatementTransactionRepository links = mock(StatementTransactionRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        InboxItemStateRepository inbox = mock(InboxItemStateRepository.class);
        ObligationRefService refs = mock(ObligationRefService.class);
        TransactionLinkRepository linkRepo = mock(TransactionLinkRepository.class);
        fileStore = mock(StatementFileStore.class);
        service = new StatementDeletionService(accountRepository, statementRepository, links, transactionRepository,
                mock(TransactionService.class), linkRepo, refs, new TransactionMatcher(), new ReviewStatusManager(),
                inbox, mock(AuditLogger.class));
        service.setFileStore(fileStore);

        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setUser(user);
        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setAccount(account);
        statement.setUser(user);
        statement.setSource(StatementSource.file_upload);
        when(accountRepository.findByIdAndUser_Id(account.getId(), userId)).thenReturn(Optional.of(account));
        when(statementRepository.findByIdAndAccount_Id(statement.getId(), account.getId())).thenReturn(Optional.of(statement));
        when(links.findTransactionIdsByStatementId(statement.getId())).thenReturn(List.of());
        when(links.findLinkedToOtherStatements(anyCollection(), eq(statement.getId()))).thenReturn(Set.of());
        when(inbox.findByUserIdAndItemKey(any(), any())).thenReturn(Optional.empty());
        when(refs.refsFor(anyCollection())).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void theFileIsDeletedRightAfterTheStatementRowWithNoGrace() {
        UUID fileId = UUID.randomUUID();
        statement.setFileId(fileId);

        service.delete(account.getId(), statement.getId());

        var order = inOrder(statementRepository, fileStore);
        order.verify(statementRepository).delete(statement);
        order.verify(fileStore).deleteIfUnreferencedNow(userId, fileId);
        verify(fileStore, never()).deleteIfUnreferenced(any(), any());
    }

    @Test
    void aStatementWithoutAFileTouchesNoFile() {
        statement.setFileId(null);

        service.delete(account.getId(), statement.getId());

        verify(fileStore, never()).deleteIfUnreferencedNow(any(), any());
    }

    @Test
    void deletingStillWorksWhenNoFileStoreIsConfigured() {
        statement.setFileId(UUID.randomUUID());
        service.setFileStore(null);

        service.delete(account.getId(), statement.getId());

        verify(statementRepository).delete(statement);
    }
}
