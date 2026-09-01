package com.financeos.domain.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.financeos.api.transaction.dto.BulkReattributeCardRequest;
import com.financeos.api.transaction.dto.BulkReattributeResponse;
import com.financeos.api.transaction.dto.CreateTransactionRequest;
import com.financeos.api.transaction.dto.UpdateTransactionRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class TransactionCardAttributionTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CardRepository cardRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReviewStatusManager reviewStatusManager;
    @Mock
    private CategorizationService categorizationService;
    @Mock
    private TransactionLinkRepository transactionLinkRepository;
    @Mock
    private StatementTransactionRepository statementTransactionRepository;

    private TransactionService transactionService;
    private UUID userId;
    private User user;
    private Account account;
    private Account otherAccount;
    private Card validCard;
    private Card invalidCard;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                null,
                transactionLinkRepository,
                statementTransactionRepository,
                null,
                cardRepository,
                null
        );

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        account = new Account("Infinia", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setUser(user);

        otherAccount = new Account("Regalia", AccountType.credit_card);
        otherAccount.setId(UUID.randomUUID());
        otherAccount.setUser(user);

        validCard = new Card();
        validCard.setId(UUID.randomUUID());
        validCard.setAccount(account);
        validCard.setLast4("1234");

        invalidCard = new Card();
        invalidCard.setId(UUID.randomUUID());
        invalidCard.setAccount(otherAccount);
        invalidCard.setLast4("9999");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createTransaction_withCardFromAnotherAccount_throwsValidationException() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findWithCardholderById(invalidCard.getId())).thenReturn(Optional.of(invalidCard));

        CreateTransactionRequest req = new CreateTransactionRequest(
                account.getId(),
                invalidCard.getId(),
                LocalDate.now(),
                new BigDecimal("-500.00"),
                "Spend",
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> transactionService.createTransaction(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Card does not belong to the transaction's account");
    }

    @Test
    void updateTransaction_validCard_updatesCardAttribution() {
        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setAccount(account);
        txn.setUser(user);
        txn.setDate(LocalDate.now());
        txn.setAmount(new BigDecimal("500.00"));
        txn.setType(TransactionType.DEBIT);

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(cardRepository.findWithCardholderById(validCard.getId())).thenReturn(Optional.of(validCard));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        UpdateTransactionRequest req = new UpdateTransactionRequest(
                account.getId(),
                validCard.getId(),
                LocalDate.now(),
                new BigDecimal("-500.00"),
                "Spend",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        Transaction updated = transactionService.updateTransaction(txn.getId(), req);

        assertThat(updated.getCard()).isEqualTo(validCard);
    }

    @Test
    void bulkReattributeCard_success() {
        Transaction t1 = new Transaction();
        t1.setId(UUID.randomUUID());
        t1.setAccount(account);
        t1.setUser(user);

        Transaction t2 = new Transaction();
        t2.setId(UUID.randomUUID());
        t2.setAccount(account);
        t2.setUser(user);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findById(validCard.getId())).thenReturn(Optional.of(validCard));
        when(transactionRepository.findForBulkReattribute(userId, account.getId(), null, null, null))
                .thenReturn(List.of(t1, t2));
        when(transactionRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        BulkReattributeCardRequest req = new BulkReattributeCardRequest(
                account.getId(),
                validCard.getId(),
                null,
                null,
                null
        );

        BulkReattributeResponse res = transactionService.bulkReattributeCard(req);

        assertThat(res.updatedCount()).isEqualTo(2);
        assertThat(t1.getCard()).isEqualTo(validCard);
        assertThat(t2.getCard()).isEqualTo(validCard);
    }

    @Test
    void bulkReattributeCard_targetCardFromDifferentAccount_throwsValidationException() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(cardRepository.findById(invalidCard.getId())).thenReturn(Optional.of(invalidCard));

        BulkReattributeCardRequest req = new BulkReattributeCardRequest(
                account.getId(),
                invalidCard.getId(),
                null,
                null,
                null
        );

        assertThatThrownBy(() -> transactionService.bulkReattributeCard(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Target card does not belong to the specified account");
    }
}
