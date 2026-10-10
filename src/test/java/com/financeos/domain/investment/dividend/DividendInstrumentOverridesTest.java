package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.api.investment.dto.UnrecordedDividendCreditsResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.UserInstrumentOverride;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dividend answers name the instrument as the reading user does (their own overrides). */
class DividendInstrumentOverridesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private TransactionRepository transactionRepository;
    private AssetClassOverrideService overrideService;
    private DividendReceiptStatusResolver resolver;
    private final UUID userId = UUID.randomUUID();
    private User user;
    private Instrument infy;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(AppTime.zone()).toInstant(), AppTime.zone()));
        transactionRepository = mock(TransactionRepository.class);
        overrideService = mock(AssetClassOverrideService.class);
        resolver = new DividendReceiptStatusResolver(transactionRepository, overrideService);
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        infy = new Instrument();
        infy.setId(UUID.randomUUID());
        infy.setName("Infosys Limited");
        infy.setSymbol("INFY");
        UserInstrumentOverride row = new UserInstrumentOverride(userId, infy.getId());
        row.setName("My Infy");
        row.setSymbol("MINFY");
        when(overrideService.overridesForCurrentUser()).thenReturn(InstrumentOverrides.of(List.of(row)));
        when(transactionRepository.findMaxDateByAccountType(AccountType.bank_account)).thenReturn(TODAY);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private Holding holding() {
        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        Holding h = new Holding(broker, infy, null);
        h.setId(UUID.randomUUID());
        h.setUser(user);
        return h;
    }

    private Dividend dividend() {
        Dividend d = new Dividend();
        d.setId(UUID.randomUUID());
        d.setUser(user);
        d.setHolding(holding());
        d.setType(DividendType.dividend);
        d.setAmount(BigDecimal.TEN);
        d.setSource("manual");
        d.setPayDate(TODAY.minusDays(3));
        return d;
    }

    @Test
    void theContextCarriesTheReadersOverridesIntoEachResponse() {
        DividendReceiptStatusResolver.Context ctx = resolver.context();
        DividendResponse r = resolver.toResponse(dividend(), ctx);
        assertEquals("My Infy", r.instrumentName());
        assertEquals("MINFY", r.symbol());
        assertEquals("My Infy", resolver.toResponse(dividend()).instrumentName());
    }

    @Test
    void withoutTheServiceOrForATwoFieldContextTheCatalogShows() {
        DividendReceiptStatusResolver plain = new DividendReceiptStatusResolver(transactionRepository);
        assertSame(InstrumentOverrides.NONE, plain.overrides());
        assertEquals("Infosys Limited", plain.toResponse(dividend()).instrumentName());
        DividendReceiptStatusResolver.Context bare = new DividendReceiptStatusResolver.Context(TODAY, TODAY);
        assertEquals("Infosys Limited", resolver.toResponse(dividend(), bare).instrumentName());
    }

    @Test
    void unrecordedCreditHintsNameTheHoldingAsTheReaderDoes() {
        HoldingRepository holdingRepository = mock(HoldingRepository.class);
        TransactionReferenceValidator validator = mock(TransactionReferenceValidator.class);
        TransactionLinkRepository links = mock(TransactionLinkRepository.class);
        DividendReceiptService service = new DividendReceiptService(mock(DividendRepository.class), transactionRepository,
                links, holdingRepository, validator, resolver);
        Account bank = new Account();
        bank.setId(UUID.randomUUID());
        bank.setType(AccountType.bank_account);
        Transaction credit = new Transaction();
        credit.setId(UUID.randomUUID());
        credit.setUser(user);
        credit.setAccount(bank);
        credit.setType(TransactionType.CREDIT);
        credit.setAmount(BigDecimal.TEN);
        credit.setDate(TODAY);
        credit.setSourcedDescription("ACH INFY DIVIDEND");
        when(transactionRepository.findDividendLikeCredits(any(), any(), any())).thenReturn(List.of(credit));
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of());
        when(links.findDistinctByMembers_Transaction_IdIn(any())).thenReturn(List.of());
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(holding()));

        UnrecordedDividendCreditsResponse r = service.scanUnrecordedCredits(null, null);

        UnrecordedDividendCreditsResponse.DividendHoldingHint hint = r.items().get(0).holdingHints().get(0);
        assertEquals("My Infy", hint.instrumentName());
        assertEquals("MINFY", hint.symbol());
    }

    @Test
    void noHoldingsMeansNoOverrideLookup() {
        HoldingRepository holdingRepository = mock(HoldingRepository.class);
        TransactionReferenceValidator validator = mock(TransactionReferenceValidator.class);
        DividendReceiptService service = new DividendReceiptService(mock(DividendRepository.class), transactionRepository,
                mock(TransactionLinkRepository.class), holdingRepository, validator, resolver);
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of());
        when(transactionRepository.findDividendLikeCredits(any(), any(), any())).thenReturn(List.of());

        service.scanUnrecordedCredits(null, null);

        verify(overrideService, never()).overridesForCurrentUser();
    }
}
