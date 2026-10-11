package com.financeos.domain.investment.sip;

import com.financeos.api.investment.dto.CreateSipRequest;
import com.financeos.api.investment.dto.SipResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A SIP and the broker account it is created on belong to the signed-in user: another user's SIP id
 * and another user's broker account id both read as not found, exactly like a missing id.
 */
class SipServiceOwnershipTest {

    private SipRepository sipRepository;
    private AccountRepository accountRepository;
    private InstrumentRepository instrumentRepository;
    private SipService sipService;

    private final UUID me = UUID.randomUUID();
    private final UUID someoneElse = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sipRepository = mock(SipRepository.class);
        accountRepository = mock(AccountRepository.class);
        instrumentRepository = mock(InstrumentRepository.class);
        AssetClassOverrideService overrideService = mock(AssetClassOverrideService.class);
        when(overrideService.overridesForCurrentUser()).thenReturn(InstrumentOverrides.NONE);
        sipService = new SipService(sipRepository, accountRepository, instrumentRepository,
                mock(HoldingRepository.class), mock(InvestmentTransactionRepository.class),
                mock(UserRepository.class), overrideService);
        UserContext.setCurrentUserId(me);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void getSip_returnsMyOwnSip() {
        Sip mine = sip(me);
        when(sipRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

        SipResponse response = sipService.getSip(mine.getId());

        assertEquals(mine.getId(), response.id());
    }

    @Test
    void getSip_anotherUsersSipIsNotFound() {
        Sip theirs = sip(someoneElse);
        when(sipRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertThrows(ResourceNotFoundException.class, () -> sipService.getSip(theirs.getId()));
    }

    @Test
    void getSip_aSipWithoutAnOwnerIsNotFound() {
        Sip ownerless = sip(null);
        when(sipRepository.findById(ownerless.getId())).thenReturn(Optional.of(ownerless));

        assertThrows(ResourceNotFoundException.class, () -> sipService.getSip(ownerless.getId()));
    }

    @Test
    void getSip_aMissingSipIsNotFound() {
        UUID missing = UUID.randomUUID();
        when(sipRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> sipService.getSip(missing));
    }

    @Test
    void createSip_onAnotherUsersBrokerAccountIsNotFoundAndSavesNothing() {
        UUID theirAccount = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(theirAccount, me)).thenReturn(Optional.empty());
        CreateSipRequest request = new CreateSipRequest(theirAccount, UUID.randomUUID(), new BigDecimal("1000"),
                SipFrequency.monthly, 5, LocalDate.of(2026, 1, 5), null, true, null);

        assertThrows(ResourceNotFoundException.class, () -> sipService.createSip(request));

        verify(accountRepository).findByIdAndUser_Id(theirAccount, me);
        verify(instrumentRepository, never()).findById(any());
        verify(sipRepository, never()).save(any());
    }

    private Sip sip(UUID ownerId) {
        Sip sip = new Sip();
        sip.setId(UUID.randomUUID());
        if (ownerId != null) {
            User owner = new User();
            owner.setId(ownerId);
            sip.setUser(owner);
        }
        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setType(AccountType.broker);
        broker.setName("Broker");
        sip.setBrokerAccount(broker);
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setName("Fund");
        sip.setInstrument(instrument);
        sip.setAmount(new BigDecimal("1000"));
        sip.setFrequency(SipFrequency.monthly);
        sip.setDayOfMonth(5);
        sip.setStartDate(LocalDate.of(2026, 1, 5));
        sip.setActive(false);
        return sip;
    }
}
