package com.financeos.domain.lending;

import com.financeos.api.lending.dto.LendingMatchSuggestionsResponse;
import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.obligation.MatchingConstants;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LendingMatchSuggestionsTest {

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

        when(counterpartyRepository.findById(counterparty.getId())).thenReturn(Optional.of(counterparty));
        when(transactionValidator.getAllReferencedTransactionIds()).thenReturn(Set.of());
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Lending entry(LendingDirection direction, BigDecimal amount, LocalDate entryDate, Instant createdAt) {
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setUser(user);
        l.setCounterparty(counterparty);
        l.setDirection(direction);
        l.setAmount(amount);
        l.setEntryDate(entryDate);
        l.setCreatedAt(createdAt);
        return l;
    }

    private Transaction candidate(TransactionType type, BigDecimal amount, LocalDate date) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAccount(account);
        t.setType(type);
        t.setAmount(amount);
        t.setDate(date);
        return t;
    }

    private void stubCandidatesForAnyEntry(TransactionType type, List<Transaction> candidates) {
        when(transactionRepository.findMatchCandidates(eq(type), any(), any(), any(), any())).thenReturn(candidates);
    }

    // --- counterparty ownership --------------------------------------------------------------

    @Test
    void getMatchSuggestions_unknownCounterparty_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(counterpartyRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> lendingService.getMatchSuggestions(unknown));
    }

    @Test
    void getMatchSuggestions_foreignCounterparty_throwsValidation() {
        Counterparty foreign = new Counterparty();
        foreign.setId(UUID.randomUUID());
        User other = new User();
        other.setId(UUID.randomUUID());
        foreign.setUser(other);
        when(counterpartyRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThrows(ValidationException.class, () -> lendingService.getMatchSuggestions(foreign.getId()));
    }

    // --- unlinked filtering ---------------------------------------------------------------------

    @Test
    void getMatchSuggestions_noUnlinkedEntries_emptyAndNoCandidateQuery() {
        Lending linked = entry(LendingDirection.lent, new BigDecimal("100.00"), LocalDate.of(2026, 1, 1), Instant.now());
        linked.setTransaction(candidate(TransactionType.DEBIT, new BigDecimal("100.00"), LocalDate.of(2026, 1, 1)));
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(linked));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        assertTrue(response.suggestions().isEmpty());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void getMatchSuggestions_linkedEntriesSkipped_onlyUnlinkedProcessed() {
        Lending linked = entry(LendingDirection.lent, new BigDecimal("100.00"), LocalDate.of(2026, 1, 1), Instant.now());
        linked.setTransaction(candidate(TransactionType.DEBIT, new BigDecimal("100.00"), LocalDate.of(2026, 1, 1)));
        Lending unlinked = entry(LendingDirection.lent, new BigDecimal("200.00"), LocalDate.of(2026, 1, 5), Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(linked, unlinked));
        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of());

        lendingService.getMatchSuggestions(counterparty.getId());

        verify(transactionRepository, times(1)).findMatchCandidates(eq(TransactionType.DEBIT), any(), any(), any(), any());
    }

    // --- candidate query bounds ------------------------------------------------------------------

    @Test
    void getMatchSuggestions_lentEntry_queriesDebitWithinToleranceAndWindow() {
        LocalDate entryDate = LocalDate.of(2026, 3, 10);
        BigDecimal amount = new BigDecimal("500.00");
        Lending unlinked = entry(LendingDirection.lent, amount, entryDate, Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(unlinked));
        when(transactionRepository.findMatchCandidates(any(), any(), any(), any(), any())).thenReturn(List.of());

        lendingService.getMatchSuggestions(counterparty.getId());

        verify(transactionRepository).findMatchCandidates(
                TransactionType.DEBIT,
                amount.subtract(MatchingConstants.MATCH_AMOUNT_TOLERANCE),
                amount.add(MatchingConstants.MATCH_AMOUNT_TOLERANCE),
                entryDate.minusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS),
                entryDate.plusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS));
    }

    @Test
    void getMatchSuggestions_borrowedEntry_queriesCreditWithinToleranceAndWindow() {
        LocalDate entryDate = LocalDate.of(2026, 4, 20);
        BigDecimal amount = new BigDecimal("300.00");
        Lending unlinked = entry(LendingDirection.borrowed, amount, entryDate, Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(unlinked));
        when(transactionRepository.findMatchCandidates(any(), any(), any(), any(), any())).thenReturn(List.of());

        lendingService.getMatchSuggestions(counterparty.getId());

        verify(transactionRepository).findMatchCandidates(
                TransactionType.CREDIT,
                amount.subtract(MatchingConstants.MATCH_AMOUNT_TOLERANCE),
                amount.add(MatchingConstants.MATCH_AMOUNT_TOLERANCE),
                entryDate.minusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS),
                entryDate.plusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS));
    }

    // --- exclusion filters -----------------------------------------------------------------------

    @Test
    void getMatchSuggestions_alreadyReferencedCandidate_dropped() {
        Lending unlinked = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1), Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(unlinked));

        Transaction referenced = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1));
        Transaction free = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1));
        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of(referenced, free));
        when(transactionValidator.getAllReferencedTransactionIds()).thenReturn(Set.of(referenced.getId()));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        List<TransactionResponse> candidates = response.suggestions().get(0).candidates();
        assertEquals(1, candidates.size());
        assertEquals(free.getId(), candidates.get(0).id());
    }

    @Test
    void getMatchSuggestions_candidateInTransactionLink_dropped() {
        Lending unlinked = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1), Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(unlinked));

        Transaction linkedTx = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1));
        Transaction free = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1));
        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of(linkedTx, free));

        TransactionLink link = new TransactionLink();
        link.setId(UUID.randomUUID());
        link.setType(LinkType.TRANSFER);
        link.getMembers().add(new TransactionLinkMember(link, linkedTx, true));
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(any())).thenReturn(List.of(link));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        List<TransactionResponse> candidates = response.suggestions().get(0).candidates();
        assertEquals(1, candidates.size());
        assertEquals(free.getId(), candidates.get(0).id());
    }

    @Test
    void getMatchSuggestions_zeroCandidateEntries_omittedFromResponse() {
        Lending unlinked = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1), Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(unlinked));
        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of());

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        assertTrue(response.suggestions().isEmpty());
    }

    // --- greedy assignment -----------------------------------------------------------------------

    @Test
    void getMatchSuggestions_greedy_assignsSharedCandidateToNearerEntry() {
        Lending entryA = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1), Instant.now());
        Lending entryB = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 10), Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(entryA, entryB));

        // Transaction dated 2026-01-09 is 8 days from A, 1 day from B -> should go to B.
        Transaction shared = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), LocalDate.of(2026, 1, 9));
        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of(shared));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        assertEquals(1, response.suggestions().size());
        assertEquals(entryB.getId(), response.suggestions().get(0).lendingId());
        assertEquals(1, response.suggestions().get(0).candidates().size());
        assertEquals(shared.getId(), response.suggestions().get(0).candidates().get(0).id());
    }

    @Test
    void getMatchSuggestions_greedy_tieGoesToEarlierEntry() {
        Instant earlierCreated = Instant.parse("2026-01-01T00:00:00Z");
        Instant laterCreated = Instant.parse("2026-01-02T00:00:00Z");
        Lending entryA = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1), earlierCreated);
        Lending entryB = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1), laterCreated);
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(entryA, entryB));

        // Same entryDate on both -> equal distance to the candidate -> tie broken by sort order (A first).
        Transaction shared = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1));
        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of(shared));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        assertEquals(1, response.suggestions().size());
        assertEquals(entryA.getId(), response.suggestions().get(0).lendingId());
    }

    @Test
    void getMatchSuggestions_exclusiveCandidateStillAssignedToOtherEntry() {
        Lending entryA = entry(LendingDirection.lent, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1), Instant.now());
        Lending entryB = entry(LendingDirection.lent, new BigDecimal("300.00"), LocalDate.of(2026, 2, 1), Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(entryA, entryB));

        Transaction shared = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), LocalDate.of(2026, 1, 1));
        Transaction exclusiveToB = candidate(TransactionType.DEBIT, new BigDecimal("300.00"), LocalDate.of(2026, 2, 1));

        // Distinct amount/date bounds per entry -> stub per exact call using the entry's own tolerance window.
        when(transactionRepository.findMatchCandidates(
                eq(TransactionType.DEBIT),
                eq(new BigDecimal("500.00").subtract(MatchingConstants.MATCH_AMOUNT_TOLERANCE)),
                eq(new BigDecimal("500.00").add(MatchingConstants.MATCH_AMOUNT_TOLERANCE)),
                eq(LocalDate.of(2026, 1, 1).minusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS)),
                eq(LocalDate.of(2026, 1, 1).plusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS))))
                .thenReturn(List.of(shared));
        when(transactionRepository.findMatchCandidates(
                eq(TransactionType.DEBIT),
                eq(new BigDecimal("300.00").subtract(MatchingConstants.MATCH_AMOUNT_TOLERANCE)),
                eq(new BigDecimal("300.00").add(MatchingConstants.MATCH_AMOUNT_TOLERANCE)),
                eq(LocalDate.of(2026, 2, 1).minusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS)),
                eq(LocalDate.of(2026, 2, 1).plusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS))))
                .thenReturn(List.of(exclusiveToB));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        assertEquals(2, response.suggestions().size());
        var suggestionForA = response.suggestions().stream().filter(s -> s.lendingId().equals(entryA.getId())).findFirst().orElseThrow();
        var suggestionForB = response.suggestions().stream().filter(s -> s.lendingId().equals(entryB.getId())).findFirst().orElseThrow();
        assertEquals(shared.getId(), suggestionForA.candidates().get(0).id());
        assertEquals(exclusiveToB.getId(), suggestionForB.candidates().get(0).id());
    }

    // --- within-entry ordering -------------------------------------------------------------------

    @Test
    void getMatchSuggestions_candidatesOrderedByAmountDiffThenDateDiff() {
        LocalDate entryDate = LocalDate.of(2026, 1, 10);
        BigDecimal amount = new BigDecimal("500.00");
        Lending unlinked = entry(LendingDirection.lent, amount, entryDate, Instant.now());
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(unlinked));

        // farAmount: amount diff 20, date diff 0 -> worst amount match
        Transaction farAmount = candidate(TransactionType.DEBIT, new BigDecimal("520.00"), entryDate);
        // closeAmountFarDate: amount diff 0, date diff 5 -> best amount, worse date than exact
        Transaction closeAmountFarDate = candidate(TransactionType.DEBIT, amount, entryDate.plusDays(5));
        // exact: amount diff 0, date diff 0 -> best overall
        Transaction exact = candidate(TransactionType.DEBIT, amount, entryDate);

        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of(farAmount, closeAmountFarDate, exact));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        List<TransactionResponse> candidates = response.suggestions().get(0).candidates();
        assertEquals(3, candidates.size());
        assertEquals(exact.getId(), candidates.get(0).id());
        assertEquals(closeAmountFarDate.getId(), candidates.get(1).id());
        assertEquals(farAmount.getId(), candidates.get(2).id());
    }

    // --- response field mapping ------------------------------------------------------------------

    @Test
    void getMatchSuggestions_responseFieldsMapped() {
        LocalDate entryDate = LocalDate.of(2026, 1, 10);
        LocalDate expectedReturn = LocalDate.of(2026, 3, 10);
        Lending unlinked = entry(LendingDirection.lent, new BigDecimal("500.00"), entryDate, Instant.now());
        unlinked.setExpectedReturnDate(expectedReturn);
        unlinked.setNotes("business trip");
        when(lendingRepository.findByCounterpartyIdWithRefs(counterparty.getId())).thenReturn(List.of(unlinked));

        Transaction match = candidate(TransactionType.DEBIT, new BigDecimal("500.00"), entryDate);
        stubCandidatesForAnyEntry(TransactionType.DEBIT, List.of(match));

        LendingMatchSuggestionsResponse response = lendingService.getMatchSuggestions(counterparty.getId());

        var suggestion = response.suggestions().get(0);
        assertEquals(unlinked.getId(), suggestion.lendingId());
        assertEquals(LendingDirection.lent, suggestion.direction());
        assertEquals(new BigDecimal("500.00"), suggestion.amount());
        assertEquals(entryDate, suggestion.entryDate());
        assertEquals(expectedReturn, suggestion.expectedReturnDate());
        assertEquals("business trip", suggestion.notes());
        assertEquals(1, suggestion.candidates().size());
        assertEquals(match.getId(), suggestion.candidates().get(0).id());
    }
}
