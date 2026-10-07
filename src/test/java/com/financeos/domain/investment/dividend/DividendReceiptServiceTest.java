package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.ConfirmDividendMatchesRequest;
import com.financeos.api.investment.dto.ConfirmDividendMatchesResponse;
import com.financeos.api.investment.dto.DividendReceiptSummaryResponse;
import com.financeos.api.investment.dto.DividendReconciliationResponse;
import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.api.investment.dto.UnrecordedDividendCreditsResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DividendReceiptServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    private DividendRepository dividendRepository;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository transactionLinkRepository;
    private HoldingRepository holdingRepository;
    private TransactionReferenceValidator validator;
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
        holdingRepository = mock(HoldingRepository.class);
        validator = mock(TransactionReferenceValidator.class);
        service = new DividendReceiptService(dividendRepository, transactionRepository, transactionLinkRepository,
                holdingRepository, validator, new DividendReceiptStatusResolver(transactionRepository));

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

    // --- builders -------------------------------------------------------------------------------------

    private Holding holding(Instrument instrument) {
        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        broker.setType(AccountType.broker);
        Holding h = new Holding(broker, instrument, null);
        h.setId(UUID.randomUUID());
        h.setUser(user);
        return h;
    }

    private Dividend dividend(String gross, String tds, String source, LocalDate exDate, LocalDate payDate, Instant createdAt) {
        Dividend d = new Dividend();
        d.setId(UUID.randomUUID());
        d.setUser(user);
        d.setHolding(holding(infy));
        d.setType(DividendType.dividend);
        d.setAmount(new BigDecimal(gross));
        d.setTds(tds == null ? null : new BigDecimal(tds));
        d.setSource(source);
        d.setExDate(exDate);
        d.setPayDate(payDate);
        d.setCreatedAt(createdAt);
        when(dividendRepository.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    private Dividend manualDividend(String gross, LocalDate payDate) {
        return dividend(gross, null, "manual", null, payDate, Instant.parse("2026-01-01T00:00:00Z"));
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

    // --- link -------------------------------------------------------------------------------------------

    @Test
    void link_requiresTransactionId() {
        assertThrows(ValidationException.class, () -> service.linkTransaction(UUID.randomUUID(), null, false));
    }

    @Test
    void link_unknownDividendIs404() {
        UUID id = UUID.randomUUID();
        when(dividendRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.linkTransaction(id, UUID.randomUUID(), false));
    }

    @Test
    void link_foreignDividendIs404AndNeverTouchesTheValidator() {
        Dividend d = manualDividend("1000", TODAY);
        User other = new User();
        other.setId(UUID.randomUUID());
        d.setUser(other);
        assertThrows(ResourceNotFoundException.class, () -> service.linkTransaction(d.getId(), UUID.randomUUID(), false));
        verify(validator, never()).validateForDividend(any());
    }

    @Test
    void link_setsTransactionClearsManualNoteAndReportsReceived() {
        Dividend d = manualDividend("1000", TODAY.minusDays(30));
        d.setReceiptStatus(DividendReceiptStatus.not_received);
        Transaction t = credit("1000", TODAY.minusDays(28), "INFY DIV");
        when(validator.validateForDividend(t.getId())).thenReturn(t);

        DividendResponse response = service.linkTransaction(d.getId(), t.getId(), false);

        assertSame(t, d.getTransaction());
        assertNull(d.getReceiptStatus());
        assertEquals(DividendReceiptStatus.received, response.receiptStatus());
        assertEquals(t.getId(), response.transaction().id());
        assertEquals(bank.getId(), response.transaction().accountId());
        assertEquals(0, response.transaction().signedAmount().compareTo(new BigDecimal("1000")));
        verify(dividendRepository).save(d);
    }

    @Test
    void link_sameTransactionIsANoOp() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t = credit("1000", TODAY, "x");
        d.setTransaction(t);

        DividendResponse response = service.linkTransaction(d.getId(), t.getId(), true);

        assertEquals(DividendReceiptStatus.received, response.receiptStatus());
        verify(validator, never()).validateForDividend(any());
        verify(dividendRepository, never()).save(any());
    }

    @Test
    void link_validatorRejectionPropagates() {
        Dividend d = manualDividend("1000", TODAY);
        UUID txId = UUID.randomUUID();
        when(validator.validateForDividend(txId)).thenThrow(new ValidationException("nope"));
        assertThrows(ValidationException.class, () -> service.linkTransaction(d.getId(), txId, false));
        verify(dividendRepository, never()).save(any());
    }

    @Test
    void link_updateTds_writesTheGapWhenItLooksLikeTds() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t = credit("900", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);

        DividendResponse response = service.linkTransaction(d.getId(), t.getId(), true);

        assertEquals(new BigDecimal("100.00"), d.getTds());
        assertEquals(new BigDecimal("100.00"), response.tds());
    }

    @Test
    void link_updateTds_skipsWhenNothingWasDeducted() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t = credit("1000", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);
        service.linkTransaction(d.getId(), t.getId(), true);
        assertNull(d.getTds());
    }

    @Test
    void link_updateTds_skipsWhenTheGapIsTooLargeToBeTds() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t = credit("700", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);
        service.linkTransaction(d.getId(), t.getId(), true);
        assertNull(d.getTds());
    }

    @Test
    void link_updateTds_neverOverwritesRecordedTds() {
        Dividend d = dividend("1000", "50", "manual", null, TODAY, Instant.now());
        Transaction t = credit("900", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);
        service.linkTransaction(d.getId(), t.getId(), true);
        assertEquals(new BigDecimal("50"), d.getTds());
    }

    @Test
    void link_updateTdsOff_leavesTdsAlone() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t = credit("900", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);
        service.linkTransaction(d.getId(), t.getId(), false);
        assertNull(d.getTds());
    }

    // --- unlink ---------------------------------------------------------------------------------------

    @Test
    void unlink_isIdempotentWhenNothingIsLinked() {
        Dividend d = manualDividend("1000", TODAY);
        service.unlinkTransaction(d.getId());
        verify(dividendRepository, never()).save(any());
    }

    @Test
    void unlink_clearsTheTransaction() {
        Dividend d = manualDividend("1000", TODAY);
        d.setTransaction(credit("1000", TODAY, "x"));
        service.unlinkTransaction(d.getId());
        assertNull(d.getTransaction());
        verify(dividendRepository).save(d);
    }

    // --- manual receipt status -----------------------------------------------------------------------

    @Test
    void setReceiptStatus_rejectsDerivedValues() {
        Dividend d = manualDividend("1000", TODAY);
        for (DividendReceiptStatus derived : List.of(DividendReceiptStatus.received, DividendReceiptStatus.awaiting,
                DividendReceiptStatus.overdue, DividendReceiptStatus.unverifiable)) {
            assertThrows(ValidationException.class, () -> service.setReceiptStatus(d.getId(), derived), derived.name());
        }
        verify(dividendRepository, never()).save(any());
    }

    @Test
    void setReceiptStatus_rejectsAManualNoteOnALinkedRow() {
        Dividend d = manualDividend("1000", TODAY);
        d.setTransaction(credit("1000", TODAY, "x"));
        assertThrows(ValidationException.class, () -> service.setReceiptStatus(d.getId(), DividendReceiptStatus.not_received));
    }

    @Test
    void setReceiptStatus_storesTheManualValueAndReportsIt() {
        Dividend d = manualDividend("1000", TODAY.minusDays(100));
        DividendResponse response = service.setReceiptStatus(d.getId(), DividendReceiptStatus.received_untracked);
        assertEquals(DividendReceiptStatus.received_untracked, d.getReceiptStatus());
        assertEquals(DividendReceiptStatus.received_untracked, response.receiptStatus());
    }

    @Test
    void setReceiptStatus_nullClearsTheNoteEvenOnALinkedRow() {
        Dividend d = manualDividend("1000", TODAY);
        d.setReceiptStatus(DividendReceiptStatus.not_received);
        d.setTransaction(credit("1000", TODAY, "x"));
        DividendResponse response = service.setReceiptStatus(d.getId(), null);
        assertNull(d.getReceiptStatus());
        assertEquals(DividendReceiptStatus.received, response.receiptStatus());
    }

    @Test
    void setReceiptStatus_foreignRowIs404() {
        Dividend d = manualDividend("1000", TODAY);
        User other = new User();
        other.setId(UUID.randomUUID());
        d.setUser(other);
        assertThrows(ResourceNotFoundException.class, () -> service.setReceiptStatus(d.getId(), DividendReceiptStatus.not_received));
    }

    // --- reconciliation -------------------------------------------------------------------------------

    @Test
    void reconciliation_noUnresolvedRows_returnsEmptyWithCoverage() {
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of());
        DividendReconciliationResponse r = service.getReconciliation(null, null, null);
        assertTrue(r.items().isEmpty());
        assertEquals(TODAY, r.coverageEnd());
        assertEquals(0, r.unresolvedCount());
        assertEquals(0, r.withCandidates());
        verify(transactionRepository, never()).findCreditCandidates(any(), any(), any(), any(), any());
    }

    @Test
    void reconciliation_queriesTheAmountBandAndSourceWindowOnReceivingAccounts() {
        Dividend d = dividend("1000", null, "suggested", TODAY.minusDays(20), TODAY.minusDays(20), Instant.now());
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(d));
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any())).thenReturn(List.of());

        service.getReconciliation(null, null, null);

        verify(transactionRepository).findCreditCandidates(
                eq(DividendReceiptService.RECEIVING_ACCOUNT_TYPES),
                eq(new BigDecimal("775.00")), eq(new BigDecimal("10105.00")),
                eq(TODAY.minusDays(23)), eq(TODAY.plusDays(40)));
    }

    @Test
    void reconciliation_skipsZeroAmountRowsWithoutQuerying() {
        Dividend d = manualDividend("0", TODAY);
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(d));
        DividendReconciliationResponse r = service.getReconciliation(null, null, null);
        assertTrue(r.items().isEmpty());
        assertEquals(1, r.unresolvedCount());
        verify(transactionRepository, never()).findCreditCandidates(any(), any(), any(), any(), any());
    }

    @Test
    void reconciliation_excludesCreditsAlreadyReferencedOrInALinkGroup() {
        Dividend d = manualDividend("1000", TODAY.minusDays(5));
        Transaction referenced = credit("1000", TODAY.minusDays(5), "a");
        Transaction inLinkGroup = credit("1000", TODAY.minusDays(5), "b");
        Transaction free = credit("1000", TODAY.minusDays(5), "c");
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(d));
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any()))
                .thenReturn(List.of(referenced, inLinkGroup, free));
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of(referenced.getId()));
        TransactionLink link = new TransactionLink();
        link.setType(LinkType.TRANSFER);
        link.getMembers().add(new TransactionLinkMember(link, inLinkGroup, true));
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(any())).thenReturn(List.of(link));

        DividendReconciliationResponse r = service.getReconciliation(null, null, null);

        assertEquals(1, r.items().size());
        List<UUID> offered = r.items().get(0).candidates().stream().map(c -> c.transaction().id()).toList();
        assertEquals(List.of(free.getId()), offered);
    }

    @Test
    void reconciliation_greedyHandsACreditToTheDividendThatScoresItHighest() {
        Dividend near = dividend("1000", null, "manual", null, TODAY.minusDays(5), Instant.parse("2026-01-01T00:00:00Z"));
        Dividend far = dividend("1000", null, "manual", null, TODAY.minusDays(13), Instant.parse("2026-01-02T00:00:00Z"));
        Transaction t = credit("1000", TODAY.minusDays(5), "x");
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(far, near));
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any())).thenReturn(List.of(t));

        DividendReconciliationResponse r = service.getReconciliation(null, null, null);

        assertEquals(2, r.unresolvedCount());
        assertEquals(1, r.withCandidates());
        assertEquals(near.getId(), r.items().get(0).dividend().id());
        assertEquals(100, r.items().get(0).candidates().get(0).score());
    }

    @Test
    void reconciliation_tiesGoToTheNearerDateThenTheOlderRow() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        Dividend a = dividend("1000", null, "manual", null, TODAY.minusDays(5), newer);
        Dividend b = dividend("1000", null, "manual", null, TODAY.minusDays(5), older);
        Transaction t = credit("1000", TODAY.minusDays(5), "x");
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(a, b));
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any())).thenReturn(List.of(t));

        DividendReconciliationResponse r = service.getReconciliation(null, null, null);

        assertEquals(1, r.items().size());
        assertEquals(b.getId(), r.items().get(0).dividend().id());
    }

    @Test
    void reconciliation_ranksCandidatesBestFirstWithReasonsAndImpliedTds() {
        Dividend d = manualDividend("1000", TODAY.minusDays(5));
        Transaction net = credit("900", TODAY.minusDays(5), "x");
        Transaction exact = credit("1000", TODAY.minusDays(4), "INFY DIV");
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(d));
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any())).thenReturn(List.of(net, exact));

        DividendReconciliationResponse r = service.getReconciliation(null, null, null);

        List<DividendReconciliationResponse.DividendMatchCandidate> cands = r.items().get(0).candidates();
        assertEquals(2, cands.size());
        assertEquals(exact.getId(), cands.get(0).transaction().id());
        assertEquals(DividendMatchTier.EXACT, cands.get(0).tier());
        assertTrue(cands.get(0).reasons().contains(DividendMatchReason.SYMBOL_MATCH));
        assertEquals(DividendMatchTier.NET_OF_TDS, cands.get(1).tier());
        assertEquals(0, cands.get(1).impliedTds().compareTo(new BigDecimal("100")));
        assertEquals(DividendReceiptStatus.awaiting, r.items().get(0).dividend().receiptStatus());
    }

    @Test
    void reconciliation_passesFiltersThrough() {
        when(dividendRepository.findUnresolvedForReconciliation(any(), any(), any())).thenReturn(List.of());
        UUID broker = UUID.randomUUID();
        service.getReconciliation(broker, TODAY.minusDays(90), TODAY);
        verify(dividendRepository).findUnresolvedForReconciliation(broker, TODAY.minusDays(90), TODAY);
    }

    // --- confirm ----------------------------------------------------------------------------------------

    @Test
    void confirm_reportsPartialSuccessPerItem() {
        Dividend ok = manualDividend("1000", TODAY);
        Dividend bad = manualDividend("1000", TODAY);
        Transaction t1 = credit("1000", TODAY, "x");
        UUID rejectedTx = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(dividendRepository.findById(missing)).thenReturn(Optional.empty());
        when(validator.validateForDividend(t1.getId())).thenReturn(t1);
        when(validator.validateForDividend(rejectedTx)).thenThrow(new ValidationException("already linked to a loan record"));

        ConfirmDividendMatchesResponse r = service.confirmMatches(new ConfirmDividendMatchesRequest(List.of(
                new ConfirmDividendMatchesRequest.ConfirmDividendMatchItem(ok.getId(), t1.getId(), false),
                new ConfirmDividendMatchesRequest.ConfirmDividendMatchItem(bad.getId(), rejectedTx, false),
                new ConfirmDividendMatchesRequest.ConfirmDividendMatchItem(missing, t1.getId(), false))));

        assertEquals(1, r.linked().size());
        assertEquals(ok.getId(), r.linked().get(0).id());
        assertEquals(2, r.skipped().size());
        Map<UUID, String> reasons = r.skipped().stream()
                .collect(Collectors.toMap(ConfirmDividendMatchesResponse.SkippedDividendMatch::dividendId, ConfirmDividendMatchesResponse.SkippedDividendMatch::reason));
        assertTrue(reasons.get(bad.getId()).contains("loan record"));
        assertNotNull(reasons.get(missing));
    }

    // --- summary ---------------------------------------------------------------------------------------

    @Test
    void summary_bucketsEveryRowByDerivedStatus() {
        when(transactionRepository.findMaxDateByAccountType(AccountType.bank_account)).thenReturn(TODAY.minusDays(30));
        UUID txId = UUID.randomUUID();
        List<Object[]> rows = List.of(
                // linked: received, 900 arrived against 1000 gross
                new Object[]{"manual", null, TODAY.minusDays(40), null, new BigDecimal("1000"), null, txId, new BigDecimal("900")},
                // manual notes
                new Object[]{"manual", null, TODAY.minusDays(40), DividendReceiptStatus.not_received, new BigDecimal("200"), null, null, null},
                new Object[]{"manual", null, TODAY.minusDays(40), DividendReceiptStatus.received_untracked, new BigDecimal("300"), new BigDecimal("30"), null, null},
                // window still open
                new Object[]{"manual", null, TODAY, null, new BigDecimal("400"), null, null, null},
                // window closed 50 days ago, coverage reaches 30 days ago → overdue
                new Object[]{"import", null, TODAY.minusDays(60), null, new BigDecimal("500"), null, null, null},
                // window closed 2 days ago but coverage stops 30 days ago → unverifiable
                new Object[]{"manual", null, TODAY.minusDays(12), null, new BigDecimal("600"), null, null, null});
        when(dividendRepository.findReceiptRowsForSummary(null, null, null, null)).thenReturn(rows);

        DividendReceiptSummaryResponse r = service.getReceiptSummary(null, null, null, null);

        assertEquals(6, r.totalCount());
        assertEquals(TODAY.minusDays(30), r.coverageEnd());
        assertEquals(DividendReceiptStatus.values().length, r.buckets().size());
        Map<DividendReceiptStatus, DividendReceiptSummaryResponse.DividendReceiptBucket> by = r.buckets().stream()
                .collect(Collectors.toMap(DividendReceiptSummaryResponse.DividendReceiptBucket::status, b -> b));
        assertEquals(1, by.get(DividendReceiptStatus.received).count());
        assertEquals(0, by.get(DividendReceiptStatus.received).expectedNet().compareTo(new BigDecimal("1000")));
        assertEquals(0, by.get(DividendReceiptStatus.received).receivedAmount().compareTo(new BigDecimal("900")));
        assertEquals(1, by.get(DividendReceiptStatus.not_received).count());
        assertEquals(0, by.get(DividendReceiptStatus.received_untracked).expectedNet().compareTo(new BigDecimal("270")));
        assertEquals(1, by.get(DividendReceiptStatus.awaiting).count());
        assertEquals(1, by.get(DividendReceiptStatus.overdue).count());
        assertEquals(0, by.get(DividendReceiptStatus.overdue).expectedNet().compareTo(new BigDecimal("500")));
        assertEquals(1, by.get(DividendReceiptStatus.unverifiable).count());
        assertEquals(0, by.get(DividendReceiptStatus.unverifiable).receivedAmount().compareTo(BigDecimal.ZERO));
    }

    // --- unrecorded credits ----------------------------------------------------------------------------

    @Test
    void unrecorded_defaultsToTheLastYear() {
        when(transactionRepository.findDividendLikeCredits(any(), any(), any())).thenReturn(List.of());
        UnrecordedDividendCreditsResponse r = service.scanUnrecordedCredits(null, null);
        assertEquals(TODAY.minusDays(365), r.from());
        assertEquals(TODAY, r.to());
        verify(transactionRepository).findDividendLikeCredits(DividendReceiptService.RECEIVING_ACCOUNT_TYPES, TODAY.minusDays(365), TODAY);
    }

    @Test
    void unrecorded_rejectsAnInvertedRange() {
        assertThrows(ValidationException.class, () -> service.scanUnrecordedCredits(TODAY, TODAY.minusDays(1)));
    }

    @Test
    void unrecorded_dropsReferencedNonKeywordAndLinkGroupCredits() {
        Transaction referenced = credit("100", TODAY.minusDays(1), "ACH C- ITC LTD DIV");
        Transaction noKeyword = credit("100", TODAY.minusDays(2), "UPI/INDIVIDUAL/PAYMENT");
        Transaction inGroup = credit("100", TODAY.minusDays(3), "HDFC BANK DIVIDEND");
        Transaction keep = credit("100", TODAY.minusDays(4), "NEFT INFOSYS LTD DIVIDEND");
        when(transactionRepository.findDividendLikeCredits(any(), any(), any()))
                .thenReturn(List.of(referenced, noKeyword, inGroup, keep));
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of(referenced.getId()));
        TransactionLink link = new TransactionLink();
        link.setType(LinkType.TRANSFER);
        link.getMembers().add(new TransactionLinkMember(link, inGroup, true));
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(any())).thenReturn(List.of(link));
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of());

        UnrecordedDividendCreditsResponse r = service.scanUnrecordedCredits(null, null);

        assertEquals(1, r.items().size());
        assertEquals(keep.getId(), r.items().get(0).transaction().id());
        assertTrue(r.items().get(0).holdingHints().isEmpty());
    }

    @Test
    void unrecorded_hintsAreThresholdedSortedAndCapped() {
        Transaction t = credit("100", TODAY, "NEFT-HDFC BANK LTD-DIVIDEND");
        when(transactionRepository.findDividendLikeCredits(any(), any(), any())).thenReturn(List.of(t));
        Instrument hdfcBank = named("HDFC Bank Limited", "HDFCBANK");      // {hdfc, bank} → 2/2 = 1.0
        Instrument hdfcBankAlt = named("HDFC Bank Limited", "HDFCBANK");   // same instrument at a second broker → 1.0
        Instrument hdfcSec = named("HDFC Securities", "HDFCSEC");          // {hdfc, securities} → 1/2 = 0.5
        Instrument bob = named("Bank of Baroda", "BANKBARODA");            // {bank, baroda} → 1/2 = 0.5
        Instrument hdfcAmc = named("HDFC Asset Management", "HDFCAMC");    // 1/3 → below threshold
        Instrument itc = named("ITC Limited", "ITC");                      // 0
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(
                holding(hdfcAmc), holding(itc), holding(hdfcSec), holding(hdfcBank), holding(bob), holding(hdfcBankAlt)));

        UnrecordedDividendCreditsResponse r = service.scanUnrecordedCredits(null, null);

        // four holdings clear the threshold; the cap keeps the best three, ties ordered by name
        List<UnrecordedDividendCreditsResponse.DividendHoldingHint> hints = r.items().get(0).holdingHints();
        assertEquals(3, hints.size());
        assertEquals(1.0, hints.get(0).nameScore());
        assertEquals(1.0, hints.get(1).nameScore());
        assertEquals(0.5, hints.get(2).nameScore());
        assertEquals("Bank of Baroda", hints.get(2).instrumentName());
    }

    @Test
    void unrecorded_newestCreditsFirst() {
        Transaction old = credit("100", TODAY.minusDays(10), "A DIV");
        Transaction recent = credit("100", TODAY.minusDays(1), "B DIV");
        when(transactionRepository.findDividendLikeCredits(any(), any(), any())).thenReturn(List.of(old, recent));
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of());

        UnrecordedDividendCreditsResponse r = service.scanUnrecordedCredits(null, null);

        assertEquals(List.of(recent.getId(), old.getId()), r.items().stream().map(i -> i.transaction().id()).toList());
    }

    private static Instrument named(String name, String symbol) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setSymbol(symbol);
        return i;
    }

    @Test
    void applyTdsFromReceipt_roundsToPaise() {
        Dividend d = new Dividend();
        d.setAmount(new BigDecimal("1000.0000"));
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal("899.9950"));
        assertTrue(DividendReceiptService.applyTdsFromReceipt(d, t));
        assertEquals(new BigDecimal("100.01"), d.getTds());
    }

    @Test
    void reconciliation_candidatesCarryTheReceivingAccount() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t = credit("1000", TODAY, "x");
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(d));
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any())).thenReturn(List.of(t));
        ArgumentCaptor<Dividend> none = ArgumentCaptor.forClass(Dividend.class);

        DividendReconciliationResponse r = service.getReconciliation(null, null, null);

        assertEquals(bank.getId(), r.items().get(0).candidates().get(0).transaction().accountId());
        verify(dividendRepository, never()).save(none.capture());
    }

    // --- review gaps ------------------------------------------------------------------------------------

    @Test
    void reconciliation_equalScoresGoToTheDividendWithTheNearerBaseDate() {
        // same score (no keyword/name hits, both within 3 days → penalty 0), different distance
        Dividend near = dividend("1000", null, "manual", null, TODAY.minusDays(5), Instant.parse("2026-02-01T00:00:00Z"));
        Dividend far = dividend("1000", null, "manual", null, TODAY.minusDays(8), Instant.parse("2026-01-01T00:00:00Z"));
        Transaction t = credit("1000", TODAY.minusDays(5), "x");
        when(dividendRepository.findUnresolvedForReconciliation(null, null, null)).thenReturn(List.of(far, near));
        when(transactionRepository.findCreditCandidates(any(), any(), any(), any(), any())).thenReturn(List.of(t));

        DividendReconciliationResponse r = service.getReconciliation(null, null, null);

        assertEquals(1, r.items().size());
        assertEquals(near.getId(), r.items().get(0).dividend().id()); // older `far` loses: distance decides before age
    }

    @Test
    void link_updateTds_acceptsAGapOfExactlyTwentyFivePercent() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t = credit("750", TODAY, "x");
        when(validator.validateForDividend(t.getId())).thenReturn(t);
        service.linkTransaction(d.getId(), t.getId(), true);
        assertEquals(new BigDecimal("250.00"), d.getTds());
    }

    @Test
    void confirm_skipsADividendRepeatedInTheSameBatch() {
        Dividend d = manualDividend("1000", TODAY);
        Transaction t1 = credit("1000", TODAY, "a");
        Transaction t2 = credit("1000", TODAY, "b");
        when(validator.validateForDividend(t1.getId())).thenReturn(t1);
        when(validator.validateForDividend(t2.getId())).thenReturn(t2);

        ConfirmDividendMatchesResponse r = service.confirmMatches(new ConfirmDividendMatchesRequest(List.of(
                new ConfirmDividendMatchesRequest.ConfirmDividendMatchItem(d.getId(), t1.getId(), false),
                new ConfirmDividendMatchesRequest.ConfirmDividendMatchItem(d.getId(), t2.getId(), false))));

        assertEquals(1, r.linked().size());
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).reason().contains("more than once"));
        assertSame(t1, d.getTransaction()); // the first item won; the second did not overwrite it
    }

    @Test
    void summary_countsACreditSharedByTwoDividendsOnce() {
        UUID sharedTxn = UUID.randomUUID();
        List<Object[]> rows = List.of(
                new Object[]{"manual", null, TODAY.minusDays(1), null, new BigDecimal("600"), null, sharedTxn, new BigDecimal("900")},
                new Object[]{"manual", null, TODAY.minusDays(1), null, new BigDecimal("400"), null, sharedTxn, new BigDecimal("900")});
        when(dividendRepository.findReceiptRowsForSummary(null, null, null, null)).thenReturn(rows);

        DividendReceiptSummaryResponse r = service.getReceiptSummary(null, null, null, null);

        DividendReceiptSummaryResponse.DividendReceiptBucket received = r.buckets().stream()
                .filter(b -> b.status() == DividendReceiptStatus.received).findFirst().orElseThrow();
        assertEquals(2, received.count());
        assertEquals(0, received.expectedNet().compareTo(new BigDecimal("1000")));
        assertEquals(0, received.receivedAmount().compareTo(new BigDecimal("900"))); // not 1800
    }
}
