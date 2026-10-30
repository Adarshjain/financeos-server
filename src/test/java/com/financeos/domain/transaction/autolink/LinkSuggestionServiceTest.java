package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.linksuggestion.dto.LinkSuggestionResponse;
import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLinkService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LinkSuggestionServiceTest {

    private final AutoLinkStore store = mock(AutoLinkStore.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final TransactionLinkService linkService = mock(TransactionLinkService.class);
    private final LinkSuggestionService service = new LinkSuggestionService(store, transactions, linkService);
    private final UUID userId = UUID.randomUUID();

    private static Transaction tx(AccountType accountType, TransactionType type) {
        Account account = new Account("Acc " + accountType, accountType);
        account.setId(UUID.randomUUID());
        Transaction t = new Transaction(account, LocalDate.of(2026, 10, 1), new BigDecimal("100"), "desc",
                TransactionSource.manual, type, false, false);
        t.setId(UUID.randomUUID());
        return t;
    }

    private AutoLinkStore.SuggestionRow row(SuggestionKind kind, Transaction main, Transaction... candidates) {
        return new AutoLinkStore.SuggestionRow(UUID.randomUUID(), kind, main.getId(),
                java.util.Arrays.stream(candidates).map(Transaction::getId).toList());
    }

    private void load(Transaction... all) {
        when(transactions.findAllByIdInAndUserId(any(), eq(userId))).thenReturn(List.of(all));
    }

    @Test
    void acceptCreatesAUserTransferLinkWithTheDebitAsAnchorAndDeletesTheSuggestion() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction credit = tx(AccountType.bank_account, TransactionType.CREDIT);
        AutoLinkStore.SuggestionRow row = row(SuggestionKind.TRANSFER, debit, credit);
        when(store.findSuggestion(userId, row.id())).thenReturn(Optional.of(row));
        load(debit, credit);

        service.accept(userId, row.id(), credit.getId());

        ArgumentCaptor<CreateTransactionLinkRequest> request = ArgumentCaptor.forClass(CreateTransactionLinkRequest.class);
        verify(linkService).createLink(request.capture(), eq(LinkOrigin.USER));
        assertThat(request.getValue().type()).isEqualTo(LinkType.TRANSFER);
        assertThat(request.getValue().members().get(0).transactionId()).isEqualTo(debit.getId());
        assertThat(request.getValue().members().get(0).isAnchor()).isTrue();
        assertThat(request.getValue().members().get(1).transactionId()).isEqualTo(credit.getId());
        verify(store).deleteSuggestion(userId, row.id());
    }

    @Test
    void acceptOfACardCandidateLinksAsACardPayment() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction credit = tx(AccountType.credit_card, TransactionType.CREDIT);
        AutoLinkStore.SuggestionRow row = row(SuggestionKind.TRANSFER, debit, credit);
        when(store.findSuggestion(userId, row.id())).thenReturn(Optional.of(row));
        load(debit, credit);

        service.accept(userId, row.id(), credit.getId());

        ArgumentCaptor<CreateTransactionLinkRequest> request = ArgumentCaptor.forClass(CreateTransactionLinkRequest.class);
        verify(linkService).createLink(request.capture(), eq(LinkOrigin.USER));
        assertThat(request.getValue().type()).isEqualTo(LinkType.CC_PAYMENT);
    }

    @Test
    void acceptOfARefundAnchorsTheChosenDebitAndCounterpartsTheCredit() {
        Transaction credit = tx(AccountType.credit_card, TransactionType.CREDIT);
        Transaction debit = tx(AccountType.credit_card, TransactionType.DEBIT);
        AutoLinkStore.SuggestionRow row = row(SuggestionKind.REFUND, credit, debit);
        when(store.findSuggestion(userId, row.id())).thenReturn(Optional.of(row));
        load(credit, debit);

        service.accept(userId, row.id(), debit.getId());

        ArgumentCaptor<CreateTransactionLinkRequest> request = ArgumentCaptor.forClass(CreateTransactionLinkRequest.class);
        verify(linkService).createLink(request.capture(), eq(LinkOrigin.USER));
        assertThat(request.getValue().type()).isEqualTo(LinkType.REFUND);
        assertThat(request.getValue().members().get(0).transactionId()).isEqualTo(debit.getId());
        assertThat(request.getValue().members().get(0).isAnchor()).isTrue();
        assertThat(request.getValue().members().get(1).transactionId()).isEqualTo(credit.getId());
    }

    @Test
    void acceptRejectsANonCandidateAndAnUnknownSuggestion() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction credit = tx(AccountType.bank_account, TransactionType.CREDIT);
        AutoLinkStore.SuggestionRow row = row(SuggestionKind.TRANSFER, debit, credit);
        when(store.findSuggestion(userId, row.id())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.accept(userId, row.id(), UUID.randomUUID())).isInstanceOf(ValidationException.class);
        UUID unknown = UUID.randomUUID();
        when(store.findSuggestion(userId, unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept(userId, unknown, credit.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(linkService, never()).createLink(any(), any());
        verify(store, never()).deleteSuggestion(any(), any());
    }

    @Test
    void dismissRecordsARejectionPerCandidateWithTheKindItWouldLinkAsAndDeletesTheRow() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction bank = tx(AccountType.bank_account, TransactionType.CREDIT);
        Transaction card = tx(AccountType.credit_card, TransactionType.CREDIT);
        AutoLinkStore.SuggestionRow row = row(SuggestionKind.TRANSFER, debit, bank, card);
        when(store.findSuggestion(userId, row.id())).thenReturn(Optional.of(row));
        load(bank, card);

        service.dismiss(userId, row.id());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AutoLinkStore.Rejection>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(store).addRejections(eq(userId), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                new AutoLinkStore.Rejection(SuggestionKind.TRANSFER, debit.getId(), bank.getId()),
                new AutoLinkStore.Rejection(SuggestionKind.CC_PAYMENT, debit.getId(), card.getId()));
        verify(store).deleteSuggestion(userId, row.id());
    }

    @Test
    void dismissOfARefundSuggestionRejectsCreditAgainstEachDebit() {
        Transaction credit = tx(AccountType.credit_card, TransactionType.CREDIT);
        Transaction debit = tx(AccountType.credit_card, TransactionType.DEBIT);
        AutoLinkStore.SuggestionRow row = row(SuggestionKind.REFUND, credit, debit);
        when(store.findSuggestion(userId, row.id())).thenReturn(Optional.of(row));
        load(debit);

        service.dismiss(userId, row.id());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AutoLinkStore.Rejection>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(store).addRejections(eq(userId), captor.capture());
        assertThat(captor.getValue()).containsExactly(new AutoLinkStore.Rejection(SuggestionKind.REFUND, credit.getId(), debit.getId()));
    }

    @Test
    void dismissOfAnotherUsersOrUnknownSuggestionIs404() {
        UUID id = UUID.randomUUID();
        when(store.findSuggestion(userId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dismiss(userId, id)).isInstanceOf(ResourceNotFoundException.class);
        verify(store, never()).deleteSuggestion(any(), any());
    }

    @Test
    void listBatchesTheTransactionLoadAndSkipsLinkedOnesAndSuggestionsWithoutCandidates() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction credit = tx(AccountType.bank_account, TransactionType.CREDIT);
        Transaction linkedCredit = tx(AccountType.bank_account, TransactionType.CREDIT);
        Transaction orphan = tx(AccountType.bank_account, TransactionType.DEBIT);
        AutoLinkStore.SuggestionRow good = row(SuggestionKind.TRANSFER, debit, credit, linkedCredit);
        AutoLinkStore.SuggestionRow onlyLinkedCandidate = row(SuggestionKind.TRANSFER, orphan, linkedCredit);
        when(store.listSuggestions(userId)).thenReturn(List.of(good, onlyLinkedCandidate));
        load(debit, credit, linkedCredit, orphan);
        when(store.liveTransactionIds(eq(userId), any())).thenReturn(java.util.Set.of(debit.getId(), credit.getId(), orphan.getId()));

        List<LinkSuggestionResponse> out = service.list(userId);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(good.id());
        assertThat(out.get(0).transaction().id()).isEqualTo(debit.getId());
        assertThat(out.get(0).transaction().accountName()).isEqualTo(debit.getAccount().getName());
        assertThat(out.get(0).candidates()).extracting(LinkSuggestionResponse.TransactionSummary::id).containsExactly(credit.getId());
        // One transaction load for every id of every suggestion, not one per suggestion.
        verify(transactions).findAllByIdInAndUserId(any(), eq(userId));
    }

    @Test
    void openCountsMatchTheListedSuggestions() {
        Transaction debit = tx(AccountType.bank_account, TransactionType.DEBIT);
        Transaction credit = tx(AccountType.bank_account, TransactionType.CREDIT);
        Transaction linkedCredit = tx(AccountType.bank_account, TransactionType.CREDIT);
        Transaction orphan = tx(AccountType.bank_account, TransactionType.DEBIT);
        when(store.listSuggestions(userId)).thenReturn(List.of(row(SuggestionKind.TRANSFER, debit, credit),
                row(SuggestionKind.TRANSFER, orphan, linkedCredit)));
        when(store.liveTransactionIds(eq(userId), any())).thenReturn(java.util.Set.of(debit.getId(), credit.getId(), orphan.getId()));

        assertThat(service.openCounts(userId)).containsOnly(java.util.Map.entry(SuggestionKind.TRANSFER, 1));
    }
}
