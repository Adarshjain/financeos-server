package com.financeos.domain.investment.dividend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.investment.dividend.DividendReceiptService.UniqueMatch;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkStore;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Auto-link side of {@link DividendReceiptService}: the unique-both-ways matches, the AUTO origin and the rejection on unlink. */
class DividendAutoLinkTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    private DividendRepository dividendRepository;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository transactionLinkRepository;
    private TransactionReferenceValidator validator;
    private AutoLinkStore autoLinkStore;
    private DividendReceiptService service;

    private UUID userId;
    private User user;
    private Account bank;
    private Instrument infy;

    @BeforeEach
    void setUp() {
        dividendRepository = mock(DividendRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionLinkRepository = mock(TransactionLinkRepository.class);
        validator = mock(TransactionReferenceValidator.class);
        autoLinkStore = mock(AutoLinkStore.class);
        service = new DividendReceiptService(dividendRepository, transactionRepository, transactionLinkRepository,
                mock(HoldingRepository.class), validator, new DividendReceiptStatusResolver(transactionRepository));
        service.setAutoLinkStore(autoLinkStore);

        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(AppTime.zone()).toInstant(), AppTime.zone()));
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        bank = new Account();
        bank.setId(UUID.randomUUID());
        bank.setName("HDFC Savings");
        bank.setType(AccountType.bank_account);
        infy = new Instrument();
        infy.setId(UUID.randomUUID());
        infy.setName("Infosys Limited");
        infy.setSymbol("INFY");

        when(transactionRepository.findMaxDateByAccountType(AccountType.bank_account)).thenReturn(TODAY);
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of());
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(any())).thenReturn(List.of());
        when(dividendRepository.save(any(Dividend.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private Dividend dividend(String gross, LocalDate payDate) {
        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        broker.setType(AccountType.broker);
        Holding h = new Holding(broker, infy, null);
        h.setId(UUID.randomUUID());
        h.setUser(user);
        Dividend d = new Dividend();
        d.setId(UUID.randomUUID());
        d.setUser(user);
        d.setHolding(h);
        d.setType(DividendType.dividend);
        d.setAmount(new BigDecimal(gross));
        d.setSource("manual");
        d.setPayDate(payDate);
        d.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(dividendRepository.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    private Transaction credit(String amount, LocalDate date, String description) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setAccount(bank);
        t.setType(TransactionType.CREDIT);
        t.setAmount(new BigDecimal(amount));
        t.setDate(date);
        t.setSourcedDescription(description);
        return t;
    }

    private void world(List<Dividend> dividends, List<Transaction> credits) {
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(dividends);
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any())).thenReturn(credits);
    }

    // --- findUniqueMatches ------------------------------------------------------------------------------

    @Test
    void anExactUniquePairIsReturned() {
        Dividend d = dividend("1000", TODAY.minusDays(5));
        Transaction t = credit("1000", TODAY.minusDays(5), "INFY DIV");
        world(List.of(d), List.of(t));

        List<UniqueMatch> matches = service.findUniqueMatches((a, b) -> false);

        assertEquals(1, matches.size());
        assertEquals(d.getId(), matches.get(0).dividendId());
        assertEquals(t.getId(), matches.get(0).transactionId());
        assertEquals(DividendMatchTier.EXACT, matches.get(0).tier());
    }

    @Test
    void aNetOfTdsUniquePairIsReturned() {
        Dividend d = dividend("1000", TODAY.minusDays(5));
        Transaction t = credit("900", TODAY.minusDays(5), "INFY DIV");
        world(List.of(d), List.of(t));

        List<UniqueMatch> matches = service.findUniqueMatches((a, b) -> false);

        assertEquals(1, matches.size());
        assertEquals(DividendMatchTier.NET_OF_TDS, matches.get(0).tier());
    }

    @Test
    void aDividendWithTwoCandidateCreditsIsNotReturned() {
        Dividend d = dividend("1000", TODAY.minusDays(5));
        world(List.of(d), List.of(credit("1000", TODAY.minusDays(5), "a"), credit("1000", TODAY.minusDays(4), "b")));

        assertTrue(service.findUniqueMatches((a, b) -> false).isEmpty());
    }

    @Test
    void aCreditFittingTwoDividendsIsNotReturned() {
        Dividend a = dividend("1000", TODAY.minusDays(5));
        Dividend b = dividend("1000", TODAY.minusDays(5));
        world(List.of(a, b), List.of(credit("1000", TODAY.minusDays(5), "x")));

        assertTrue(service.findUniqueMatches((x, y) -> false).isEmpty());
    }

    @Test
    void aRejectedPairIsSkipped() {
        Dividend d = dividend("1000", TODAY.minusDays(5));
        Transaction t = credit("1000", TODAY.minusDays(5), "INFY DIV");
        world(List.of(d), List.of(t));

        assertTrue(service.findUniqueMatches((div, tx) -> div.equals(d.getId()) && tx.equals(t.getId())).isEmpty());
    }

    @Test
    void aFuzzyTierIsNotReturnedEvenWhenUnique() {
        Dividend d = dividend("1000", TODAY.minusDays(5));
        // 800 is inside the fuzzy band and the narration names the company with a dividend keyword
        Transaction t = credit("800", TODAY.minusDays(5), "INFOSYS LIMITED DIVIDEND");
        world(List.of(d), List.of(t));

        assertTrue(service.findUniqueMatches((a, b) -> false).isEmpty());
    }

    @Test
    void aCreditInALinkGroupIsNotReturned() {
        Dividend d = dividend("1000", TODAY.minusDays(5));
        Transaction t = credit("1000", TODAY.minusDays(5), "INFY DIV");
        world(List.of(d), List.of(t));
        TransactionLink link = new TransactionLink();
        TransactionLinkMember member = new TransactionLinkMember();
        member.setTransaction(t);
        link.setMembers(Set.of(member));
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(any())).thenReturn(List.of(link));

        assertTrue(service.findUniqueMatches((a, b) -> false).isEmpty());
    }

    @Test
    void noUnresolvedDividendsNeverQueriesCredits() {
        world(List.of(), List.of());

        assertTrue(service.findUniqueMatches((a, b) -> false).isEmpty());
        verify(transactionRepository, never()).findCreditCandidates(any(), any(), any(), any(), any());
    }

    // --- origin and rejection ---------------------------------------------------------------------------

    @Test
    void autoLinkSetsTheCreditTheAutoOriginAndWritesTds() {
        Dividend d = dividend("1000", TODAY);
        Transaction t = credit("900", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);

        DividendResponse response = service.autoLinkTransaction(d.getId(), t.getId());

        assertSame(t, d.getTransaction());
        assertEquals(LinkOrigin.AUTO, d.getTransactionOrigin());
        assertEquals(new BigDecimal("100.00"), d.getTds());
        assertEquals(LinkOrigin.AUTO, response.transactionOrigin());
    }

    @Test
    void aManualLinkIsAUserLink() {
        Dividend d = dividend("1000", TODAY);
        d.setTransactionOrigin(LinkOrigin.AUTO);
        Transaction t = credit("1000", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);

        service.linkTransaction(d.getId(), t.getId(), false);

        assertEquals(LinkOrigin.USER, d.getTransactionOrigin());
    }

    @Test
    void unlinkingAnAutoLinkRemembersThePairAndResetsTheOrigin() {
        Dividend d = dividend("1000", TODAY);
        Transaction t = credit("1000", TODAY, "x");
        d.setTransaction(t);
        d.setTransactionOrigin(LinkOrigin.AUTO);

        service.unlinkTransaction(d.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AutoLinkStore.Rejection>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(autoLinkStore).addRejections(eq(userId), captor.capture());
        assertEquals(List.of(new AutoLinkStore.Rejection(SuggestionKind.DIVIDEND, t.getId(), d.getId())),
                List.copyOf(captor.getValue()));
        assertNull(d.getTransaction());
        assertEquals(LinkOrigin.USER, d.getTransactionOrigin());
    }

    @Test
    void unlinkingAUserLinkRecordsNothing() {
        Dividend d = dividend("1000", TODAY);
        d.setTransaction(credit("1000", TODAY, "x"));
        d.setTransactionOrigin(LinkOrigin.USER);

        service.unlinkTransaction(d.getId());

        verify(autoLinkStore, never()).addRejections(any(), anyCollection());
    }

    @Test
    void manuallyReplacingAnAutoLinkRemembersTheOldPair() {
        Dividend d = dividend("1000", TODAY);
        Transaction old = credit("1000", TODAY, "x");
        d.setTransaction(old);
        d.setTransactionOrigin(LinkOrigin.AUTO);
        Transaction replacement = credit("1000", TODAY, "y");
        when(validator.validateForDividend(replacement.getId())).thenReturn(replacement);

        service.linkTransaction(d.getId(), replacement.getId(), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AutoLinkStore.Rejection>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(autoLinkStore).addRejections(eq(userId), captor.capture());
        assertEquals(List.of(new AutoLinkStore.Rejection(SuggestionKind.DIVIDEND, old.getId(), d.getId())),
                List.copyOf(captor.getValue()));
        assertEquals(LinkOrigin.USER, d.getTransactionOrigin());
    }

    @Test
    void isUnlinkedIsTrueOnlyForAnOwnedDividendWithoutACredit() {
        Dividend open = dividend("1000", TODAY);
        Dividend linked = dividend("1000", TODAY);
        linked.setTransaction(credit("1000", TODAY, "x"));
        assertTrue(service.isUnlinked(open.getId()));
        assertEquals(false, service.isUnlinked(linked.getId()));
        assertEquals(false, service.isUnlinked(UUID.randomUUID()));
    }
}
