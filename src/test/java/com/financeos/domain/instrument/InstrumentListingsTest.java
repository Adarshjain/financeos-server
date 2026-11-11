package com.financeos.domain.instrument;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which catalog row an ISIN means: the repository default and the per-user listing rule. */
class InstrumentListingsTest {

    private static final String ISIN = "INE002A01018";
    private final UUID user = UUID.randomUUID();
    private final InstrumentRepository repo = mock(InstrumentRepository.class);

    private static Instrument row(String exchange, long createdAtSeconds) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setIsin(ISIN);
        i.setSymbol("RELIANCE");
        i.setExchange(exchange);
        i.setCreatedAt(Instant.ofEpochSecond(createdAtSeconds));
        return i;
    }

    private final Instrument bse = row("BSE", 100);
    private final Instrument nse = row("NSE", 200);

    @Test
    void findByIsinWithTwoRowsIsTheNseOneEvenWhenTheBseRowIsOlder() {
        when(repo.findAllByIsin(ISIN)).thenReturn(List.of(bse, nse));
        doCallRealMethod().when(repo).findByIsin(ISIN);
        assertEquals(nse, repo.findByIsin(ISIN).orElseThrow());
    }

    @Test
    void findByIsinWithoutAnNseRowIsTheOldestAndNoRowIsEmpty() {
        Instrument older = row("BSE", 1);
        when(repo.findAllByIsin(ISIN)).thenReturn(List.of(bse, older));
        doCallRealMethod().when(repo).findByIsin(ISIN);
        assertEquals(older, repo.findByIsin(ISIN).orElseThrow());
        when(repo.findAllByIsin("NONE")).thenReturn(List.of());
        doCallRealMethod().when(repo).findByIsin("NONE");
        assertTrue(repo.findByIsin("NONE").isEmpty());
    }

    @Test
    void theHeldListingWinsOverTheFilesExchange() {
        assertEquals(nse, InstrumentListings.pick(List.of(nse, bse), Set.of(nse.getId()), "BSE"));
        assertEquals(bse, InstrumentListings.pick(List.of(nse, bse), Set.of(bse.getId()), "NSE"));
    }

    @Test
    void theFilesExchangeWinsWhenNothingIsHeldAndTheDefaultOtherwise() {
        assertEquals(bse, InstrumentListings.pick(List.of(nse, bse), Set.of(), "bse"));
        assertEquals(nse, InstrumentListings.pick(List.of(nse, bse), Set.of(), null));
        assertEquals(nse, InstrumentListings.pick(List.of(nse, bse), Set.of(), "NYSE"));
    }

    @Test
    void byIsinChecksTheOtherListingOnlyAfterAnNseRowAndSkipsItForASingleRow() {
        when(repo.findByIsin(ISIN)).thenReturn(Optional.of(nse));
        when(repo.findByIsinAndExchange(ISIN, "BSE")).thenReturn(Optional.empty());
        assertEquals(nse, InstrumentListings.byIsin(repo, user, ISIN, "BSE").orElseThrow());
        verify(repo, never()).findHeldInstrumentIds(any(), any());

        when(repo.findByIsinAndExchange(ISIN, "BSE")).thenReturn(Optional.of(bse));
        assertEquals(bse, InstrumentListings.byIsin(repo, user, ISIN, "BSE").orElseThrow());
        when(repo.findHeldInstrumentIds(any(), any())).thenReturn(List.of(nse.getId()));
        assertEquals(nse, InstrumentListings.byIsin(repo, user, ISIN, "BSE").orElseThrow());
        assertEquals(nse, InstrumentListings.byIsin(repo, user, ISIN, null).orElseThrow());
    }

    @Test
    void byIsinsAppliesTheRuleInOneBatchAndLeavesSingleRowsAlone() {
        Instrument other = new Instrument();
        other.setId(UUID.randomUUID());
        other.setIsin("INE009A01021");
        other.setExchange("NSE");
        when(repo.findByIsinIn(any())).thenReturn(List.of(bse, nse, other));
        when(repo.findHeldInstrumentIds(any(), any())).thenReturn(List.of(bse.getId()));

        Map<String, Instrument> out = InstrumentListings.byIsins(repo, user,
                new java.util.LinkedHashMap<>(Map.of(ISIN, "NSE", "INE009A01021", "BSE")));

        assertEquals(bse, out.get(ISIN), "held BSE wins over the file's NSE");
        assertEquals(other, out.get("INE009A01021"));
        verify(repo).findByIsinIn(any());
        verify(repo).findHeldInstrumentIds(any(), any());
    }

    @Test
    void byIsinsWithoutAHoldingUsesTheFilesExchangeThenTheDefault() {
        when(repo.findByIsinIn(any())).thenReturn(List.of(bse, nse));
        when(repo.findHeldInstrumentIds(any(), any())).thenReturn(List.of());
        java.util.HashMap<String, String> asked = new java.util.HashMap<>();
        asked.put(ISIN, "BSE");
        assertEquals(bse, InstrumentListings.byIsins(repo, user, asked).get(ISIN));
        asked.put(ISIN, null);
        assertEquals(nse, InstrumentListings.byIsins(repo, user, asked).get(ISIN));
    }
}
