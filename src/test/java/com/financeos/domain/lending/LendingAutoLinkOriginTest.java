package com.financeos.domain.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.lending.dto.LendingResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkStore;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The AUTO origin on lending entries and the rejection recorded when an automatic link is removed. */
class LendingAutoLinkOriginTest {

    private final LendingRepository lendingRepository = mock(LendingRepository.class);
    private final TransactionReferenceValidator validator = mock(TransactionReferenceValidator.class);
    private final AutoLinkStore autoLinkStore = mock(AutoLinkStore.class);
    private final LendingService service = new LendingService(mock(CounterpartyRepository.class), lendingRepository,
            mock(UserRepository.class), validator, mock(TransactionRepository.class), mock(TransactionLinkRepository.class));

    private final UUID userId = UUID.randomUUID();
    private User user;
    private Counterparty counterparty;

    @BeforeEach
    void setUp() {
        service.setAutoLinkStore(autoLinkStore);
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        counterparty = new Counterparty();
        counterparty.setId(UUID.randomUUID());
        counterparty.setName("Rahul");
        counterparty.setUser(user);
        when(lendingRepository.save(any(Lending.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction debit() {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setType(TransactionType.DEBIT);
        t.setAmount(new BigDecimal("500"));
        t.setDate(LocalDate.of(2026, 1, 2));
        return t;
    }

    private Lending lending(LinkOrigin origin, Transaction transaction) {
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setUser(user);
        l.setCounterparty(counterparty);
        l.setDirection(LendingDirection.lent);
        l.setAmount(new BigDecimal("500"));
        l.setEntryDate(LocalDate.of(2026, 1, 1));
        l.setTransaction(transaction);
        l.setOrigin(origin);
        when(lendingRepository.findById(l.getId())).thenReturn(Optional.of(l));
        return l;
    }

    private void verifyRejected(Transaction t, Lending l) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AutoLinkStore.Rejection>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(autoLinkStore).addRejections(eq(userId), captor.capture());
        assertThat(captor.getValue()).containsExactly(new AutoLinkStore.Rejection(SuggestionKind.LENDING, t.getId(), l.getId()));
    }

    @Test
    void autoLinkTransactionLinksAndMarksTheEntryAuto() {
        Lending l = lending(LinkOrigin.USER, null);
        Transaction t = debit();
        when(validator.validateForLending(t.getId(), LendingDirection.lent)).thenReturn(t);

        LendingResponse response = service.autoLinkTransaction(l.getId(), t.getId());

        assertThat(l.getTransaction()).isSameAs(t);
        assertThat(l.getOrigin()).isEqualTo(LinkOrigin.AUTO);
        assertThat(response.origin()).isEqualTo(LinkOrigin.AUTO);
    }

    @Test
    void aManualLinkIsAUserLink() {
        Lending l = lending(LinkOrigin.AUTO, null);
        Transaction t = debit();
        when(validator.validateForLending(t.getId(), LendingDirection.lent)).thenReturn(t);

        service.linkTransaction(l.getId(), t.getId());

        assertThat(l.getOrigin()).isEqualTo(LinkOrigin.USER);
    }

    @Test
    void unlinkingAnAutoLinkRemembersThePairAndResetsTheOrigin() {
        Transaction t = debit();
        Lending l = lending(LinkOrigin.AUTO, t);

        service.unlinkTransaction(l.getId());

        verifyRejected(t, l);
        assertThat(l.getTransaction()).isNull();
        assertThat(l.getOrigin()).isEqualTo(LinkOrigin.USER);
    }

    @Test
    void deletingAnAutoLinkedEntryRemembersThePair() {
        Transaction t = debit();
        Lending l = lending(LinkOrigin.AUTO, t);

        service.deleteLending(l.getId());

        verifyRejected(t, l);
        verify(lendingRepository).delete(l);
    }

    @Test
    void unlinkingOrDeletingAUserLinkRecordsNothing() {
        Lending a = lending(LinkOrigin.USER, debit());
        Lending b = lending(LinkOrigin.USER, debit());

        service.unlinkTransaction(a.getId());
        service.deleteLending(b.getId());

        verify(autoLinkStore, never()).addRejections(any(), anyCollection());
    }

    @Test
    void unlinkedEntriesAreListedFromOneQuery() {
        Lending linked = lending(LinkOrigin.USER, debit());
        Lending open = lending(LinkOrigin.USER, null);
        when(lendingRepository.findAllWithRefs()).thenReturn(List.of(linked, open));

        assertThat(service.getUnlinkedEntries()).extracting(LendingService.UnlinkedEntry::id).containsExactly(open.getId());
    }

    @Test
    void manuallyReplacingAnAutoLinkRemembersTheOldPair() {
        Transaction old = debit();
        Lending l = lending(LinkOrigin.AUTO, old);
        Transaction replacement = debit();
        when(validator.validateForLending(replacement.getId(), LendingDirection.lent)).thenReturn(replacement);

        service.linkTransaction(l.getId(), replacement.getId());

        verifyRejected(old, l);
        assertThat(l.getTransaction()).isSameAs(replacement);
        assertThat(l.getOrigin()).isEqualTo(LinkOrigin.USER);
    }

    @Test
    void replacingAUserLinkRecordsNothing() {
        Lending l = lending(LinkOrigin.USER, debit());
        Transaction replacement = debit();
        when(validator.validateForLending(replacement.getId(), LendingDirection.lent)).thenReturn(replacement);

        service.linkTransaction(l.getId(), replacement.getId());

        verify(autoLinkStore, never()).addRejections(any(), anyCollection());
    }

    @Test
    void isUnlinkedIsTrueOnlyForAnOwnedEntryWithoutATransaction() {
        assertThat(service.isUnlinked(lending(LinkOrigin.USER, null).getId())).isTrue();
        assertThat(service.isUnlinked(lending(LinkOrigin.USER, debit()).getId())).isFalse();
        assertThat(service.isUnlinked(UUID.randomUUID())).isFalse();
    }
}
