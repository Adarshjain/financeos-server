package com.financeos.domain.lending;

import com.financeos.api.lending.dto.CreateLendingRequest;
import com.financeos.api.lending.dto.LendingResponse;
import com.financeos.api.lending.dto.UpdateLendingRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
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

class LendingServiceLinkTest {

    private CounterpartyRepository counterpartyRepository;
    private LendingRepository lendingRepository;
    private UserRepository userRepository;
    private TransactionReferenceValidator transactionValidator;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository transactionLinkRepository;

    private LendingService lendingService;

    private UUID userId;
    private User user;
    private Counterparty counterparty;

    @BeforeEach
    void setUp() {
        counterpartyRepository = mock(CounterpartyRepository.class);
        lendingRepository = mock(LendingRepository.class);
        userRepository = mock(UserRepository.class);
        transactionValidator = mock(TransactionReferenceValidator.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionLinkRepository = mock(TransactionLinkRepository.class);

        lendingService = new LendingService(
                counterpartyRepository, lendingRepository, userRepository, transactionValidator,
                transactionRepository, transactionLinkRepository);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        counterparty = new Counterparty();
        counterparty.setId(UUID.randomUUID());
        counterparty.setName("Rahul");
        counterparty.setUser(user);

        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(lendingRepository.save(any(Lending.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction transaction(UUID id, TransactionType type, BigDecimal amount, String description) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("HDFC Savings");

        Transaction t = new Transaction();
        t.setId(id);
        t.setAccount(account);
        t.setDate(LocalDate.of(2026, 1, 15));
        t.setAmount(amount);
        t.setType(type);
        t.setDescription(description);
        return t;
    }

    private Lending lending(UUID id, LendingDirection direction, Transaction transaction) {
        Lending l = new Lending();
        l.setId(id);
        l.setUser(user);
        l.setCounterparty(counterparty);
        l.setDirection(direction);
        l.setAmount(new BigDecimal("500.00"));
        l.setEntryDate(LocalDate.of(2026, 1, 1));
        l.setNotes("note");
        l.setTransaction(transaction);
        return l;
    }

    // --- createLending -----------------------------------------------------------------------

    @Test
    void createLending_withTransactionId_validatesAndBuildsTransactionSummary() {
        UUID txnId = UUID.randomUUID();
        Transaction txn = transaction(txnId, TransactionType.DEBIT, new BigDecimal("500.00"), null);
        txn.setSourcedDescription("ATM cash withdrawal");
        when(counterpartyRepository.findById(counterparty.getId())).thenReturn(Optional.of(counterparty));
        when(transactionValidator.validateForLending(txnId, LendingDirection.lent)).thenReturn(txn);

        CreateLendingRequest req = new CreateLendingRequest(
                counterparty.getId(), null, LendingDirection.lent, new BigDecimal("500.00"),
                LocalDate.of(2026, 1, 1), null, txnId, null);

        LendingResponse response = lendingService.createLending(req);

        verify(transactionValidator).validateForLending(txnId, LendingDirection.lent);
        assertEquals(txnId, response.transactionId());
        assertNotNull(response.transaction());
        assertEquals(txnId, response.transaction().id());
        assertEquals(txn.getAccount().getId(), response.transaction().accountId());
        assertEquals("HDFC Savings", response.transaction().accountName());
        assertEquals(txn.getDate(), response.transaction().date());
        assertEquals("ATM cash withdrawal", response.transaction().description());
        assertEquals(new BigDecimal("500.00").negate(), response.transaction().signedAmount());
    }

    @Test
    void createLending_withTransactionId_creditSignsPositive() {
        UUID txnId = UUID.randomUUID();
        Transaction txn = transaction(txnId, TransactionType.CREDIT, new BigDecimal("500.00"), "Cash deposit");
        when(counterpartyRepository.findById(counterparty.getId())).thenReturn(Optional.of(counterparty));
        when(transactionValidator.validateForLending(txnId, LendingDirection.borrowed)).thenReturn(txn);

        CreateLendingRequest req = new CreateLendingRequest(
                counterparty.getId(), null, LendingDirection.borrowed, new BigDecimal("500.00"),
                LocalDate.of(2026, 1, 1), null, txnId, null);

        LendingResponse response = lendingService.createLending(req);

        assertEquals(new BigDecimal("500.00"), response.transaction().signedAmount());
    }

    @Test
    void createLending_withoutTransactionId_validatorCalledWithNullAndResponseHasNoTransaction() {
        when(counterpartyRepository.findById(counterparty.getId())).thenReturn(Optional.of(counterparty));
        when(transactionValidator.validateForLending(null, LendingDirection.lent)).thenReturn(null);

        CreateLendingRequest req = new CreateLendingRequest(
                counterparty.getId(), null, LendingDirection.lent, new BigDecimal("500.00"),
                LocalDate.of(2026, 1, 1), null, null, null);

        LendingResponse response = lendingService.createLending(req);

        verify(transactionValidator).validateForLending(null, LendingDirection.lent);
        assertNull(response.transactionId());
        assertNull(response.transaction());
    }

    // --- updateLending -------------------------------------------------------------------------

    @Test
    void updateLending_directionChangeWhileLinked_throws() {
        UUID id = UUID.randomUUID();
        Transaction txn = transaction(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("500.00"), null);
        Lending existing = lending(id, LendingDirection.lent, txn);
        when(lendingRepository.findWithRefsById(id)).thenReturn(Optional.of(existing));

        UpdateLendingRequest req = new UpdateLendingRequest(LendingDirection.borrowed, null, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> lendingService.updateLending(id, req));
        assertTrue(ex.getMessage().contains("Unlink the transaction"));
    }

    @Test
    void updateLending_sameDirectionWhileLinked_ok() {
        UUID id = UUID.randomUUID();
        Transaction txn = transaction(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("500.00"), null);
        Lending existing = lending(id, LendingDirection.lent, txn);
        when(lendingRepository.findWithRefsById(id)).thenReturn(Optional.of(existing));

        UpdateLendingRequest req = new UpdateLendingRequest(LendingDirection.lent, null, null, null, null);

        LendingResponse response = lendingService.updateLending(id, req);
        assertEquals(txn.getId(), response.transactionId());
        verify(lendingRepository).save(existing);
    }

    @Test
    void updateLending_directionChangeWhileUnlinked_ok() {
        UUID id = UUID.randomUUID();
        Lending existing = lending(id, LendingDirection.lent, null);
        when(lendingRepository.findWithRefsById(id)).thenReturn(Optional.of(existing));

        UpdateLendingRequest req = new UpdateLendingRequest(LendingDirection.borrowed, null, null, null, null);

        LendingResponse response = lendingService.updateLending(id, req);
        assertEquals(LendingDirection.borrowed, response.direction());
    }

    @Test
    void updateLending_notesAmountDateUpdateWhileLinked_okAndLinkKept() {
        UUID id = UUID.randomUUID();
        Transaction txn = transaction(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("500.00"), null);
        Lending existing = lending(id, LendingDirection.lent, txn);
        when(lendingRepository.findWithRefsById(id)).thenReturn(Optional.of(existing));

        UpdateLendingRequest req = new UpdateLendingRequest(
                null, new BigDecimal("999.00"), LocalDate.of(2026, 2, 1), null, "updated note");

        LendingResponse response = lendingService.updateLending(id, req);

        assertEquals(new BigDecimal("999.00"), response.amount());
        assertEquals("updated note", response.notes());
        assertEquals(txn.getId(), response.transactionId());
    }

    // --- linkTransaction -----------------------------------------------------------------------

    @Test
    void linkTransaction_nullId_throws() {
        UUID lendingId = UUID.randomUUID();
        ValidationException ex = assertThrows(ValidationException.class,
                () -> lendingService.linkTransaction(lendingId, null));
        assertTrue(ex.getMessage().contains("transactionId is required"));
    }

    @Test
    void linkTransaction_unknownLending_throwsNotFound() {
        UUID lendingId = UUID.randomUUID();
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> lendingService.linkTransaction(lendingId, UUID.randomUUID()));
    }

    @Test
    void linkTransaction_foreignLending_throws() {
        UUID lendingId = UUID.randomUUID();
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        Lending foreign = lending(lendingId, LendingDirection.lent, null);
        foreign.setUser(otherUser);
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.of(foreign));

        assertThrows(ValidationException.class, () -> lendingService.linkTransaction(lendingId, UUID.randomUUID()));
    }

    @Test
    void linkTransaction_sameIdAsCurrent_returnsWithoutValidatorOrSave() {
        UUID lendingId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();
        Transaction txn = transaction(txnId, TransactionType.DEBIT, new BigDecimal("500.00"), null);
        Lending existing = lending(lendingId, LendingDirection.lent, txn);
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.of(existing));

        LendingResponse response = lendingService.linkTransaction(lendingId, txnId);

        assertEquals(txnId, response.transactionId());
        verifyNoInteractions(transactionValidator);
        verify(lendingRepository, never()).save(any());
    }

    @Test
    void linkTransaction_newId_validatesAndSaves() {
        UUID lendingId = UUID.randomUUID();
        UUID oldTxnId = UUID.randomUUID();
        UUID newTxnId = UUID.randomUUID();
        Transaction oldTxn = transaction(oldTxnId, TransactionType.DEBIT, new BigDecimal("500.00"), null);
        Transaction newTxn = transaction(newTxnId, TransactionType.DEBIT, new BigDecimal("500.00"), null);
        Lending existing = lending(lendingId, LendingDirection.lent, oldTxn);
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.of(existing));
        when(transactionValidator.validateForLending(newTxnId, LendingDirection.lent)).thenReturn(newTxn);

        LendingResponse response = lendingService.linkTransaction(lendingId, newTxnId);

        verify(transactionValidator).validateForLending(newTxnId, LendingDirection.lent);
        verify(lendingRepository).save(existing);
        assertEquals(newTxnId, response.transactionId());
    }

    @Test
    void linkTransaction_validatorThrows_nothingSaved() {
        UUID lendingId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();
        Lending existing = lending(lendingId, LendingDirection.lent, null);
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.of(existing));
        when(transactionValidator.validateForLending(txnId, LendingDirection.lent))
                .thenThrow(new ValidationException("boom"));

        assertThrows(ValidationException.class, () -> lendingService.linkTransaction(lendingId, txnId));
        verify(lendingRepository, never()).save(any());
    }

    // --- unlinkTransaction ---------------------------------------------------------------------

    @Test
    void unlinkTransaction_linked_clearsAndSaves() {
        UUID lendingId = UUID.randomUUID();
        Transaction txn = transaction(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("500.00"), null);
        Lending existing = lending(lendingId, LendingDirection.lent, txn);
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.of(existing));

        lendingService.unlinkTransaction(lendingId);

        assertNull(existing.getTransaction());
        verify(lendingRepository).save(existing);
    }

    @Test
    void unlinkTransaction_alreadyUnlinked_noSave() {
        UUID lendingId = UUID.randomUUID();
        Lending existing = lending(lendingId, LendingDirection.lent, null);
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.of(existing));

        lendingService.unlinkTransaction(lendingId);

        verify(lendingRepository, never()).save(any());
    }

    @Test
    void unlinkTransaction_unknownLending_throwsNotFound() {
        UUID lendingId = UUID.randomUUID();
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> lendingService.unlinkTransaction(lendingId));
    }

    // --- getLendings / getLendingDetail / getAllLendings ---------------------------------------

    @Test
    void getLendings_withCounterpartyId_usesScopedQuery() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        org.springframework.data.domain.Page<Lending> page = new org.springframework.data.domain.PageImpl<>(List.of());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId(), pageable)).thenReturn(page);

        lendingService.getLendings(counterparty.getId(), pageable);

        verify(lendingRepository).findByCounterpartyIdWithRefs(counterparty.getId(), pageable);
        verify(lendingRepository, never()).findAllWithRefs(any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void getLendings_withoutCounterpartyId_usesUnscopedQuery() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        org.springframework.data.domain.Page<Lending> page = new org.springframework.data.domain.PageImpl<>(List.of());
        when(lendingRepository.findAllWithRefs(pageable)).thenReturn(page);

        lendingService.getLendings(null, pageable);

        verify(lendingRepository).findAllWithRefs(pageable);
        verify(lendingRepository, never()).findByCounterpartyIdWithRefs(any(), any());
    }

    @Test
    void getLendingDetail_usesFindWithRefsById() {
        UUID lendingId = UUID.randomUUID();
        Lending existing = lending(lendingId, LendingDirection.lent, null);
        when(lendingRepository.findWithRefsById(lendingId)).thenReturn(Optional.of(existing));

        lendingService.getLendingDetail(lendingId);

        verify(lendingRepository).findWithRefsById(lendingId);
    }

    @Test
    void getAllLendings_usesUnscopedNoArgQuery() {
        when(lendingRepository.findAllWithRefs()).thenReturn(List.of());

        lendingService.getAllLendings();

        verify(lendingRepository).findAllWithRefs();
    }
}
