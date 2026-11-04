package com.financeos.domain.statement.skip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.account.dto.StatementSkipResponse;
import com.financeos.api.account.dto.StatementSkipsResponse;
import com.financeos.core.exception.DuplicateResourceException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.user.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementSkipServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private StatementSkippedPeriodRepository skipRepository;
    private StatementSkipService service;
    private User user;
    private Account card;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        skipRepository = mock(StatementSkippedPeriodRepository.class);
        service = new StatementSkipService(accountRepository, statementRepository, skipRepository);
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());
        card = account(AccountType.credit_card);
        statementsUntil(LocalDate.of(2026, 7, 15));
        when(skipRepository.save(any())).thenAnswer(inv -> {
            StatementSkippedPeriod s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Account account(AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setName("HDFC Regalia");
        a.setType(type);
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    /** Three monthly statements closing on the 15th, the last one ending on {@code lastEnd}. */
    private void statementsUntil(LocalDate lastEnd) {
        List<Statement> list = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            Statement s = new Statement();
            s.setId(UUID.randomUUID());
            s.setAccount(card);
            LocalDate end = lastEnd.minusMonths(i);
            s.setPeriodEnd(end);
            s.setPeriodStart(end.minusMonths(1).plusDays(1));
            s.setStatementType("credit_card");
            list.add(s);
        }
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(list);
    }

    private void existingSkipEnds(LocalDate... ends) {
        List<StatementSkippedPeriodRepository.SkippedEnd> rows = new ArrayList<>();
        for (LocalDate end : ends) {
            StatementSkippedPeriodRepository.SkippedEnd row = mock(StatementSkippedPeriodRepository.SkippedEnd.class);
            when(row.getAccountId()).thenReturn(card.getId());
            when(row.getPeriodEnd()).thenReturn(end);
            rows.add(row);
        }
        when(skipRepository.findEndsByAccountIds(any())).thenReturn(rows);
    }

    @Test
    void skipsTheNextProjectedCycleWithItsStart() {
        StatementSkipResponse r = service.skip(card.getId(), LocalDate.of(2026, 8, 15));

        assertEquals(LocalDate.of(2026, 7, 16), r.periodStart());
        assertEquals(LocalDate.of(2026, 8, 15), r.periodEnd());
        verify(skipRepository).save(any());
    }

    @Test
    void aLaterProjectedCycleAlsoQualifies() {
        assertEquals(LocalDate.of(2026, 8, 16), service.skip(card.getId(), LocalDate.of(2026, 9, 15)).periodStart());
    }

    @Test
    void afterASkipTheNextCycleIsTheOneThatCanBeSkipped() {
        existingSkipEnds(LocalDate.of(2026, 8, 15));

        assertEquals(LocalDate.of(2026, 8, 16), service.skip(card.getId(), LocalDate.of(2026, 9, 15)).periodStart());
        assertThrows(DuplicateResourceException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 7, 15)));
        assertThrows(ValidationException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 8, 14)));
    }

    @Test
    void repeatingASkipReturnsTheExistingOneWithoutSaving() {
        StatementSkippedPeriod existing = new StatementSkippedPeriod();
        existing.setId(UUID.randomUUID());
        existing.setPeriodStart(LocalDate.of(2026, 7, 16));
        existing.setPeriodEnd(LocalDate.of(2026, 8, 15));
        when(skipRepository.findByAccountIdAndPeriodEnd(card.getId(), existing.getPeriodEnd())).thenReturn(Optional.of(existing));

        assertEquals(existing.getId(), service.skip(card.getId(), existing.getPeriodEnd()).id());
        verify(skipRepository, never()).save(any());
    }

    @Test
    void aPeriodThatHasNotEndedYetIsRejected() {
        assertThrows(ValidationException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 10, 20)));
        assertThrows(ValidationException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 11, 15)));
        verify(skipRepository, never()).save(any());
    }

    @Test
    void aDateThatIsNotAProjectedCycleEndIsRejected() {
        assertThrows(ValidationException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 8, 20)));
    }

    @Test
    void aPeriodWithAStatementIsAConflict() {
        assertThrows(DuplicateResourceException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 6, 15)));
        assertThrows(DuplicateResourceException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 7, 15)));
        verify(skipRepository, never()).save(any());
    }

    @Test
    void onlyAnOpenCreditCardWithHistoryCanSkip() {
        Account bank = account(AccountType.bank_account);
        assertThrows(ValidationException.class, () -> service.skip(bank.getId(), LocalDate.of(2026, 8, 15)));

        card.setClosedOn(TODAY);
        assertThrows(ValidationException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 8, 15)));

        card.setClosedOn(null);
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(List.of());
        assertThrows(ValidationException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 8, 15)));
        verify(skipRepository, never()).save(any());
    }

    @Test
    void anotherUsersAccountIsNotFound() {
        UserContext.setCurrentUserId(UUID.randomUUID());

        assertThrows(ResourceNotFoundException.class, () -> service.skip(card.getId(), LocalDate.of(2026, 8, 15)));
        assertThrows(ResourceNotFoundException.class, () -> service.state(card.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.undo(card.getId(), UUID.randomUUID()));
    }

    @Test
    void undoDeletesTheSkipAndAnUnknownSkipIsNotFound() {
        StatementSkippedPeriod existing = new StatementSkippedPeriod();
        existing.setId(UUID.randomUUID());
        when(skipRepository.findByIdAndAccountId(existing.getId(), card.getId())).thenReturn(Optional.of(existing));

        service.undo(card.getId(), existing.getId());

        verify(skipRepository).delete(existing);
        assertThrows(ResourceNotFoundException.class, () -> service.undo(card.getId(), UUID.randomUUID()));
    }

    @Test
    void stateReportsTheEarliestPassedCycleAfterTheCoveredEnd() {
        StatementSkipsResponse r = service.state(card.getId());

        assertEquals(LocalDate.of(2026, 7, 16), r.missing().periodStart());
        assertEquals(LocalDate.of(2026, 8, 15), r.missing().periodEnd());
        assertEquals(LocalDate.of(2026, 8, 20), r.missing().expectedBy());
        assertEquals(List.of(), r.skipped());
    }

    @Test
    void stateAdvancesPastSkipsAndListsThemNewestFirst() {
        existingSkipEnds(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 15));
        StatementSkippedPeriod a = skipRow(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 8, 15));
        StatementSkippedPeriod b = skipRow(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 15));
        when(skipRepository.findByAccountIdOrderByPeriodEndDesc(card.getId())).thenReturn(List.of(b, a));

        StatementSkipsResponse r = service.state(card.getId());

        assertEquals(List.of(b.getId(), a.getId()), r.skipped().stream().map(StatementSkipResponse::id).toList());
        // Oct 15 has passed (today 20 Oct): it is the next cycle waiting for a statement.
        assertEquals(LocalDate.of(2026, 10, 15), r.missing().periodEnd());
    }

    @Test
    void stateHasNoMissingCycleWhenCurrentAndHidesASkipThatNowHasAStatement() {
        statementsUntil(LocalDate.of(2026, 10, 15));
        StatementSkippedPeriod covered = skipRow(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 8, 15));
        when(skipRepository.findByAccountIdOrderByPeriodEndDesc(card.getId())).thenReturn(List.of(covered));

        StatementSkipsResponse r = service.state(card.getId());

        assertNull(r.missing());
        assertEquals(List.of(), r.skipped());
    }

    @Test
    void stateOfANonCardIsEmpty() {
        Account bank = account(AccountType.bank_account);

        StatementSkipsResponse r = service.state(bank.getId());

        assertNull(r.missing());
        assertEquals(List.of(), r.skipped());
    }

    private StatementSkippedPeriod skipRow(LocalDate start, LocalDate end) {
        StatementSkippedPeriod s = new StatementSkippedPeriod();
        s.setId(UUID.randomUUID());
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setCreatedAt(Instant.now());
        return s;
    }
}
