package com.financeos.domain.transaction.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkStore;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Removing a link the system made is remembered so the same pairing is never proposed or linked again. */
class TransactionLinkRejectionTest {

    private final TransactionLinkRepository links = mock(TransactionLinkRepository.class);
    private final AutoLinkStore store = mock(AutoLinkStore.class);
    private TransactionLinkService service;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        service = new TransactionLinkService(links, mock(TransactionRepository.class), mock(UserRepository.class));
        service.setAutoLinkStore(store);
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction tx(AccountType type, TransactionType txType) {
        Account account = new Account("A", type);
        account.setId(UUID.randomUUID());
        Transaction t = new Transaction(account, LocalDate.of(2026, 10, 1), BigDecimal.TEN, "d", TransactionSource.manual, txType, false, false);
        t.setId(UUID.randomUUID());
        t.setUser(user);
        return t;
    }

    private TransactionLink link(LinkType type, LinkOrigin origin, Transaction anchor, Transaction other) {
        TransactionLink l = new TransactionLink();
        l.setId(UUID.randomUUID());
        l.setUser(user);
        l.setType(type);
        l.setCreatedBy(origin);
        l.getMembers().add(new TransactionLinkMember(l, anchor, true));
        l.getMembers().add(new TransactionLinkMember(l, other, false));
        when(links.findById(l.getId())).thenReturn(Optional.of(l));
        return l;
    }

    @Test
    void unlinkingAnAutoLinkRecordsTheKindAndBothDirectionsOfThePair() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction credit = tx(AccountType.credit_card, TransactionType.CREDIT);
        TransactionLink l = link(LinkType.CC_PAYMENT, LinkOrigin.AUTO, debit, credit);

        service.deleteLink(l.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AutoLinkStore.Rejection>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(store).addRejections(eq(userId), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                new AutoLinkStore.Rejection(SuggestionKind.CC_PAYMENT, debit.getId(), credit.getId()),
                new AutoLinkStore.Rejection(SuggestionKind.CC_PAYMENT, credit.getId(), debit.getId()));
        verify(links).delete(l);
    }

    @Test
    void unlinkingAUserLinkRecordsNothing() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction credit = tx(AccountType.bank_account, TransactionType.CREDIT);
        TransactionLink l = link(LinkType.TRANSFER, LinkOrigin.USER, debit, credit);

        service.deleteLink(l.getId());

        verify(store, never()).addRejections(any(), any());
        verify(links).delete(l);
    }
}
