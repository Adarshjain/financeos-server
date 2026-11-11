package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link InstrumentService#updateInstrument}: an NSE <-> BSE change switches listing; any other exchange change stays display-only. */
class InstrumentServiceListingSwitchTest {

    private final UUID user = UUID.randomUUID();
    private InstrumentRepository repo;
    private AssetClassOverrideService overrides;
    private InstrumentRepointService repoint;
    private InstrumentService service;
    private Instrument nse;
    private Instrument bse;

    @BeforeEach
    void setUp() {
        repo = mock(InstrumentRepository.class);
        overrides = mock(AssetClassOverrideService.class);
        repoint = mock(InstrumentRepointService.class);
        service = new InstrumentService(repo, mock(InstrumentPriceRepository.class),
                mock(InstrumentClassificationService.class), overrides, repoint);
        nse = row("NSE");
        bse = row("BSE");
        when(repo.findById(nse.getId())).thenReturn(Optional.of(nse));
        UserContext.setCurrentUserId(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Instrument row(String exchange) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName("Infosys");
        i.setSymbol("INFY");
        i.setExchange(exchange);
        i.setIsin("INE009A01021");
        i.setYahooSymbol("INFY.NS");
        return i;
    }

    private InstrumentRequest asking(String exchange) {
        return new InstrumentRequest(InstrumentType.stock, "Infosys", "INFY", exchange, "INE009A01021", null,
                "INFY.NS", null);
    }

    @Test
    void anNseToBseChangeRepointsToTheOtherListingAndAnswersWithIt() {
        when(repoint.switchListing(any(), any(), any(), any()))
                .thenReturn(new InstrumentRepointService.Result(bse, true, false, false));

        assertEquals(bse.getId(), service.updateInstrument(nse.getId(), asking("BSE"), "INFY.BO").id());

        verify(repoint).switchListing(any(), any(), any(), eq("INFY.BO"));
        verify(overrides).setDisplay(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anotherExchangeStaysADisplayOverrideOnTheSameRow() {
        assertEquals(nse.getId(), service.updateInstrument(nse.getId(), asking("NYSE")).id());

        verifyNoInteractions(repoint);
        verify(overrides).setDisplay(user, nse, "Infosys", "INFY", "NYSE", null, InstrumentType.stock);
    }

    @Test
    void aBlankExchangeOrAnUnchangedOneStaysADisplayOverride() {
        service.updateInstrument(nse.getId(), asking(""));
        service.updateInstrument(nse.getId(), asking("NSE"));

        verify(repoint, never()).switchListing(any(), any(), any(), any());
    }

    private void userSees(String exchange) {
        UserInstrumentOverride row = new UserInstrumentOverride(user, nse.getId());
        row.setExchange(exchange);
        when(overrides.overridesFor(user)).thenReturn(InstrumentOverrides.of(java.util.List.of(row)));
    }

    @Test
    void aNameOnlyEditOfALegacyBseOverrideOnAnNseRowStaysAnOverrideWithNoMove() {
        userSees("BSE");

        assertEquals(nse.getId(), service.updateInstrument(nse.getId(),
                new InstrumentRequest(InstrumentType.stock, "Infosys Ltd", "INFY", "BSE", "INE009A01021", null,
                        "INFY.NS", null)).id());

        verifyNoInteractions(repoint);
        verify(overrides).setDisplay(user, nse, "Infosys Ltd", "INFY", "BSE", null, InstrumentType.stock);
    }

    @Test
    void pickingTheCatalogExchangeAgainOverALegacyOverrideClearsItWithNoMove() {
        userSees("BSE");

        assertEquals(nse.getId(), service.updateInstrument(nse.getId(), asking("NSE")).id());

        verifyNoInteractions(repoint);
        verify(overrides).setDisplay(user, nse, "Infosys", "INFY", "NSE", null, InstrumentType.stock);
    }
}
