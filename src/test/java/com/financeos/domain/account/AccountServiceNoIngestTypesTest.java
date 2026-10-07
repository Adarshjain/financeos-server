package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.financeos.api.account.dto.CreateAccountRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Broker and generic (Wallet/Cash) accounts can never receive statements or Gmail alerts, so
 * their requests carry no ingest watermark: create stores null, update clears any stale value
 * (pre-V88 rows), and neither path touches the Gmail backfill demand or publishes an ingest event.
 */
class AccountServiceNoIngestTypesTest {

    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private GmailBackfillDemandRepository backfillDemandRepository;
    private ApplicationEventPublisher eventPublisher;
    private AccountService accountService;

    private final UUID userId = UUID.randomUUID();
    private final User user = new User();

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        StatementRepository statementRepository = mock(StatementRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
        backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        accountService = new AccountService(accountRepository,
                mock(com.financeos.domain.account.card.CardholderRepository.class),
                mock(com.financeos.domain.account.card.CardRepository.class),
                userRepository, statementRepository, transactionRepository,
                holdingValuationService, backfillDemandRepository, eventPublisher);

        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(holdingValuationService.getBrokerMarketValue(any())).thenReturn(BigDecimal.ZERO);
        when(accountRepository.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CreateAccountRequest.BrokerRequest brokerRequest() {
        return new CreateAccountRequest.BrokerRequest(
                "Zerodha", AccountType.broker, false, FinancialPosition.asset, "demat",
                "Zerodha", "ZR1234", BigDecimal.TEN);
    }

    private static CreateAccountRequest.GenericAccountRequest genericRequest() {
        return new CreateAccountRequest.GenericAccountRequest(
                "Cash", AccountType.generic, false, FinancialPosition.asset, "petty cash");
    }

    private Account existingAccount(AccountType type, LocalDate staleIngestDate) {
        Account account = new Account("Old", type);
        account.setId(UUID.randomUUID());
        account.setUser(user);
        account.setIngestFromDate(staleIngestDate);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    private void assertNoIngestSideEffects() {
        verify(backfillDemandRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    // --- request contract -------------------------------------------------------------------

    @Test
    void brokerAndGenericRequests_haveNullIngestFromDate() {
        assertNull(brokerRequest().ingestFromDate());
        assertNull(genericRequest().ingestFromDate());
    }

    // --- create -----------------------------------------------------------------------------

    @Test
    void createBroker_storesNullIngestDate_andNeverRatchetsOrPublishes() {
        Account saved = accountService.createAccount(brokerRequest());

        assertNull(saved.getIngestFromDate());
        assertEquals(AccountType.broker, saved.getType());
        assertNoIngestSideEffects();
    }

    @Test
    void createGeneric_storesNullIngestDate_andNeverRatchetsOrPublishes() {
        Account saved = accountService.createAccount(genericRequest());

        assertNull(saved.getIngestFromDate());
        assertEquals(AccountType.generic, saved.getType());
        assertNoIngestSideEffects();
    }

    // --- update -----------------------------------------------------------------------------

    @Test
    void updateBroker_clearsStaleIngestDate_withoutRatchetOrEvent() {
        Account account = existingAccount(AccountType.broker, LocalDate.of(2025, 3, 1));

        Account saved = accountService.updateAccount(account.getId(), brokerRequest());

        assertNull(saved.getIngestFromDate());
        assertEquals("Zerodha", saved.getName());
        assertNoIngestSideEffects();
    }

    @Test
    void updateGeneric_clearsStaleIngestDate_withoutRatchetOrEvent() {
        Account account = existingAccount(AccountType.generic, LocalDate.of(2025, 3, 1));

        Account saved = accountService.updateAccount(account.getId(), genericRequest());

        assertNull(saved.getIngestFromDate());
        assertEquals("Cash", saved.getName());
        assertNoIngestSideEffects();
    }

    @Test
    void updateGeneric_whenIngestDateAlreadyNull_staysNull() {
        Account account = existingAccount(AccountType.generic, null);

        Account saved = accountService.updateAccount(account.getId(), genericRequest());

        assertNull(saved.getIngestFromDate());
        assertNoIngestSideEffects();
    }
}
