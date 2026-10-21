package com.financeos.domain.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.lending.dto.LendingResponse;
import com.financeos.api.lending.dto.UnrecordedTransferResponse;
import com.financeos.core.exception.DuplicateResourceException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GET /counterparties/unrecorded-transfers rules and the settlement recording behind it. */
class LendingServiceUnrecordedTransfersTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    private CounterpartyRepository counterpartyRepository;
    private LendingRepository lendingRepository;
    private TransactionRepository transactionRepository;
    private TransactionReferenceValidator validator;
    private TransactionLinkRepository linkRepository;
    private LendingService service;
    private final List<Object[]> netRows = new ArrayList<>();
    private final List<Transaction> bankTxns = new ArrayList<>();
    private final List<Counterparty> people = new ArrayList<>();
    private int seq;

    @BeforeEach
    void setUp() {
        counterpartyRepository = mock(CounterpartyRepository.class);
        lendingRepository = mock(LendingRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        validator = mock(TransactionReferenceValidator.class);
        linkRepository = mock(TransactionLinkRepository.class);
        service = new LendingService(counterpartyRepository, lendingRepository, mock(UserRepository.class),
                validator, transactionRepository, linkRepository);
        UserContext.setCurrentUserId(USER_ID);
        AppTime.useClock(Clock.fixed(TODAY.atTime(12, 0).atZone(IST).toInstant(), IST));
        when(counterpartyRepository.findNetPositions(USER_ID)).thenReturn(netRows);
        when(counterpartyRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(people);
        when(transactionRepository.findBankTransactionsBetween(eq(USER_ID), any(), any())).thenReturn(bankTxns);
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Counterparty person(String name, String net) {
        Counterparty cp = new Counterparty();
        cp.setId(new UUID(1, ++seq));
        cp.setName(name);
        User u = new User();
        u.setId(USER_ID);
        cp.setUser(u);
        people.add(cp);
        netRows.add(new Object[] {cp.getId(), name, new BigDecimal(net)});
        when(counterpartyRepository.findById(cp.getId())).thenReturn(Optional.of(cp));
        Lending l = new Lending();
        l.setId(new UUID(2, seq));
        l.setCounterparty(cp);
        l.setDirection(new BigDecimal(net).signum() >= 0 ? LendingDirection.lent : LendingDirection.borrowed);
        l.setKind(LendingKind.principal);
        l.setAmount(new BigDecimal(net).abs());
        l.setEntryDate(TODAY.minusDays(200));
        l.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        l.setTransaction(new Transaction()); // linked: not an unlinked entry
        when(lendingRepository.findByCounterpartyIdWithRefs(cp.getId())).thenReturn(List.of(l));
        when(lendingRepository.findByCounterparty_Id(cp.getId())).thenReturn(List.of(l));
        return cp;
    }

    private Transaction txn(TransactionType type, String amount, int daysAgo, String description) {
        Transaction t = new Transaction();
        t.setId(new UUID(3, ++seq));
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setDate(TODAY.minusDays(daysAgo));
        t.setDescription(description);
        Account a = new Account();
        a.setId(UUID.randomUUID());
        t.setAccount(a);
        User u = new User();
        u.setId(USER_ID);
        t.setUser(u);
        bankTxns.add(t);
        return t;
    }

    private static final TransactionType CREDIT = TransactionType.CREDIT;
    private static final TransactionType DEBIT = TransactionType.DEBIT;

    @Test
    void creditFromAPersonWhoOwesYouIsOfferedAsBorrowed() {
        Counterparty asha = person("Asha", "1000");
        Transaction t = txn(CREDIT, "400", 3, "UPI/ASHA/ref");

        List<UnrecordedTransferResponse> out = service.getUnrecordedTransfers();

        assertEquals(1, out.size());
        assertEquals(asha.getId(), out.get(0).counterpartyId());
        assertEquals("Asha", out.get(0).counterpartyName());
        assertEquals(t.getId(), out.get(0).transaction().id());
        assertEquals(LendingDirection.borrowed, out.get(0).direction());
    }

    @Test
    void debitToAPersonYouOweIsOfferedAsLent() {
        person("Bala", "-500");
        txn(DEBIT, "500", 3, "Paid Bala");

        List<UnrecordedTransferResponse> out = service.getUnrecordedTransfers();

        assertEquals(1, out.size());
        assertEquals(LendingDirection.lent, out.get(0).direction());
    }

    @Test
    void onlyOffersWhenItReducesTheBalance() {
        person("Asha", "1000");       // owes you: a debit to her grows it
        person("Bala", "-500");       // you owe: a credit from him grows it
        person("Chirag", "0");        // settled: neither reduces
        txn(DEBIT, "100", 1, "to Asha");
        txn(CREDIT, "100", 1, "from Bala");
        txn(CREDIT, "100", 1, "from Chirag");
        txn(DEBIT, "100", 1, "to Chirag");

        assertTrue(service.getUnrecordedTransfers().isEmpty());
    }

    @Test
    void amountMayExceedTheBalanceByTheToleranceOnly() {
        person("Asha", "1000");
        Transaction ok = txn(CREDIT, "1020", 1, "Asha");
        txn(CREDIT, "1020.01", 2, "Asha");

        List<UnrecordedTransferResponse> out = service.getUnrecordedTransfers();

        assertEquals(List.of(ok.getId()), out.stream().map(o -> o.transaction().id()).toList());
    }

    @Test
    void aTransactionNamingSeveralPeopleIsOfferedForNone() {
        person("Rahul Sharma", "1000");
        person("Rahul Verma", "1000");
        txn(CREDIT, "100", 1, "UPI Rahul");                    // only one of each name's tokens: matches neither
        Transaction both = txn(CREDIT, "100", 1, "Rahul Sharma Verma");   // matches both people
        Transaction one = txn(CREDIT, "100", 1, "Rahul Sharma");

        List<UnrecordedTransferResponse> out = service.getUnrecordedTransfers();

        assertEquals(List.of(one.getId()), out.stream().map(o -> o.transaction().id()).toList());
        assertTrue(out.stream().noneMatch(o -> o.transaction().id().equals(both.getId())));
    }

    @Test
    void shortNamesAndPartialWordsNeverMatch() {
        person("Dad", "1000");
        person("Jo", "1000");
        txn(CREDIT, "100", 1, "DADAR RAILWAY");
        txn(CREDIT, "100", 1, "Jo");

        assertTrue(service.getUnrecordedTransfers().isEmpty());
    }

    @Test
    void sourcedDescriptionIsSearchedToo() {
        person("Asha", "1000");
        Transaction t = txn(CREDIT, "100", 1, "NEFT credit");
        t.setSourcedDescription("NEFT-ASHA-BANK");

        assertEquals(1, service.getUnrecordedTransfers().size());
    }

    @Test
    void referencedAndLinkedTransactionsAreLeftOut() {
        person("Asha", "1000");
        Transaction referenced = txn(CREDIT, "100", 1, "Asha");
        Transaction linked = txn(CREDIT, "100", 2, "Asha");
        Transaction free = txn(CREDIT, "100", 3, "Asha");
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of(referenced.getId()));
        TransactionLink link = mock(TransactionLink.class);
        TransactionLinkMember member = mock(TransactionLinkMember.class);
        when(member.getTransaction()).thenReturn(linked);
        when(link.getMembers()).thenReturn(Set.of(member));
        when(linkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection())).thenReturn(List.of(link));

        List<UnrecordedTransferResponse> out = service.getUnrecordedTransfers();

        assertEquals(List.of(free.getId()), out.stream().map(o -> o.transaction().id()).toList());
    }

    @Test
    void aTransactionAlreadyOfferedAsAnEntryCandidateForThatPersonIsLeftOut() {
        Counterparty asha = person("Asha", "1000");
        Transaction candidate = txn(CREDIT, "400", 1, "Asha");
        Transaction other = txn(CREDIT, "100", 2, "Asha");
        // an unlinked 400 entry for Asha whose candidate is the first transaction
        Lending unlinked = new Lending();
        unlinked.setId(UUID.randomUUID());
        unlinked.setCounterparty(asha);
        unlinked.setDirection(LendingDirection.borrowed);
        unlinked.setKind(LendingKind.settlement);
        unlinked.setAmount(new BigDecimal("400"));
        unlinked.setEntryDate(TODAY.minusDays(1));
        unlinked.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        when(lendingRepository.findAllWithRefs()).thenReturn(List.of(unlinked));
        when(transactionRepository.findMatchCandidatesAny(eq(TransactionType.CREDIT), any()))
                .thenReturn(List.of(candidate));

        List<UnrecordedTransferResponse> out = service.getUnrecordedTransfers();

        assertEquals(List.of(other.getId()), out.stream().map(o -> o.transaction().id()).toList());
    }

    @Test
    void ordersNewestFirstAndCapsAtFifty() {
        person("Asha", "100000");
        for (int i = 0; i < 60; i++) {
            txn(CREDIT, "10", i % 30, "Asha");
        }

        List<UnrecordedTransferResponse> out = service.getUnrecordedTransfers();

        assertEquals(50, out.size());
        for (int i = 1; i < out.size(); i++) {
            assertTrue(!out.get(i - 1).transaction().date().isBefore(out.get(i).transaction().date()));
        }
    }

    @Test
    void theCandidateQueryCoversTheLast90Days() {
        person("Asha", "1000");

        service.getUnrecordedTransfers();

        verify(transactionRepository).findBankTransactionsBetween(USER_ID, TODAY.minusDays(90), TODAY);
    }

    // --- recordSettlement ---

    @Test
    void recordSettlementCreatesALinkedSettlementEntry() {
        Counterparty asha = person("Asha", "1000");
        Transaction t = txn(CREDIT, "400", 3, "Asha");
        when(transactionRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(validator.validateForLending(t.getId(), LendingDirection.borrowed)).thenReturn(t);
        when(lendingRepository.save(any(Lending.class))).thenAnswer(inv -> {
            Lending l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });
        when(counterpartyRepository.findById(asha.getId())).thenReturn(Optional.of(asha));

        LendingResponse r = service.recordSettlement(asha.getId(), t.getId());

        assertEquals(LendingKind.settlement, r.kind());
        assertEquals(LendingDirection.borrowed, r.direction());
        assertEquals(0, new BigDecimal("400").compareTo(r.amount()));
        assertEquals(t.getDate(), r.entryDate());
        assertEquals(t.getId(), r.transactionId());
    }

    @Test
    void recordSettlementIs404ForMissingOrForeignTransactionOrPerson() {
        Counterparty asha = person("Asha", "1000");
        Transaction foreign = txn(CREDIT, "400", 3, "Asha");
        User other = new User();
        other.setId(UUID.randomUUID());
        foreign.setUser(other);
        when(transactionRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThrows(ResourceNotFoundException.class, () -> service.recordSettlement(asha.getId(), foreign.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.recordSettlement(asha.getId(), UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> service.recordSettlement(UUID.randomUUID(), foreign.getId()));
    }

    @Test
    void recordSettlementIs409WhenReferencedOrLinked() {
        Counterparty asha = person("Asha", "1000");
        Transaction t = txn(CREDIT, "400", 3, "Asha");
        when(transactionRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(validator.isTransactionReferenced(t.getId())).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> service.recordSettlement(asha.getId(), t.getId()));
    }

    @Test
    void recordSettlementIs400WhenItDoesNotReduceTheBalanceOrExceedsIt() {
        Counterparty asha = person("Asha", "1000");
        Transaction wrongWay = txn(DEBIT, "400", 3, "Asha");
        Transaction tooBig = txn(CREDIT, "1021", 3, "Asha");
        when(transactionRepository.findById(wrongWay.getId())).thenReturn(Optional.of(wrongWay));
        when(transactionRepository.findById(tooBig.getId())).thenReturn(Optional.of(tooBig));

        assertThrows(ValidationException.class, () -> service.recordSettlement(asha.getId(), wrongWay.getId()));
        assertThrows(ValidationException.class, () -> service.recordSettlement(asha.getId(), tooBig.getId()));
    }
}
