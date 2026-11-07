package com.financeos.api.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.account.dto.PastCardRequest;
import com.financeos.api.gmail.dto.AssignAttentionRequest;
import com.financeos.api.gmail.dto.AssignAttentionResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.PastCardService;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-email "Assign" with a past card: the email's number becomes an old card instead of an identifier. */
class GmailControllerAssignPastCardTest {

    private final GmailProcessedMessageRepository messages = mock(GmailProcessedMessageRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountIdentifierService identifiers = mock(AccountIdentifierService.class);
    private final PastCardService pastCards = mock(PastCardService.class);
    private User user;
    private GmailController controller;
    private final PastCardRequest past = new PastCardRequest(null, LocalDate.of(2021, 5, 1), LocalDate.of(2025, 4, 30));

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        AuthService auth = mock(AuthService.class);
        when(auth.getCurrentUser()).thenReturn(user);
        controller = new GmailController(null, auth, null, null, null, null, messages, null, accounts, null, null, null, identifiers);
        ReflectionTestUtils.setField(controller, "pastCardService", pastCards);
    }

    private GmailProcessedMessage unresolved(User owner) {
        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setId(UUID.randomUUID());
        gpm.setUser(owner);
        gpm.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        gpm.setExtractedLast4("3003");
        when(messages.findById(gpm.getId())).thenReturn(Optional.of(gpm));
        return gpm;
    }

    private Account account(User owner) {
        Account a = new Account("HDFC", AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setUser(owner);
        when(accounts.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void withAPastCard_theNumberBecomesAClosedCard_andNoIdentifierIsAdded() {
        GmailProcessedMessage gpm = unresolved(user);
        Account account = account(user);
        Card card = new Card();
        card.setId(UUID.randomUUID());
        UUID job = UUID.randomUUID();
        when(pastCards.addForNumber(eq(user), eq(account), eq("3003"), eq(past), eq(null)))
                .thenReturn(new PastCardService.Result(card, 2, List.of(job)));

        AssignAttentionResponse response = controller.assignAttentionItem(gpm.getId(), new AssignAttentionRequest(account.getId(), null, past)).getBody();

        assertThat(response.cardId()).isEqualTo(card.getId());
        assertThat(response.identifierId()).isNull();
        assertThat(response.reactivatedCount()).isEqualTo(2);
        assertThat(response.jobIds()).containsExactly(job);
        verify(identifiers, never()).createOrUpdateIdentifier(any(), any(), any(), any());
    }

    @Test
    void anotherUsersEmailOrAccount_isRefused() {
        User other = new User();
        other.setId(UUID.randomUUID());
        GmailProcessedMessage foreignMail = unresolved(other);
        Account mine = account(user);
        GmailProcessedMessage mineMail = unresolved(user);
        Account foreignAccount = account(other);

        org.junit.jupiter.api.Assertions.assertThrows(ValidationException.class,
                () -> controller.assignAttentionItem(foreignMail.getId(), new AssignAttentionRequest(mine.getId(), null, past)));
        org.junit.jupiter.api.Assertions.assertThrows(ValidationException.class,
                () -> controller.assignAttentionItem(mineMail.getId(), new AssignAttentionRequest(foreignAccount.getId(), null, past)));
        verify(pastCards, never()).addForNumber(any(), any(), any(), any(), any());
    }
}
