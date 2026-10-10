package com.financeos.domain.instrument;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A user's own MANUAL price wins over a feed price of the same date. */
class PricePrecedenceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 2);
    private final UUID user = UUID.randomUUID();

    private static Instrument instrument() {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        return i;
    }

    private static InstrumentPrice feed(Instrument i, LocalDate d, String close) {
        return new InstrumentPrice(i, d, new BigDecimal(close), PriceSource.YAHOO);
    }

    private InstrumentPrice own(Instrument i, LocalDate d, String close) {
        return InstrumentPrice.manual(i, user, d, new BigDecimal(close));
    }

    @Test
    void userParamIsTheIdOrANonIdStandIn() {
        assertEquals(user.toString(), PricePrecedence.userParam(user));
        assertEquals(PricePrecedence.NO_USER, PricePrecedence.userParam(null));
    }

    @Test
    void preferredPicksTheOwnRowWhicheverComesFirst() {
        Instrument i = instrument();
        InstrumentPrice f = feed(i, D1, "10");
        InstrumentPrice o = own(i, D1, "12");
        assertSame(o, PricePrecedence.preferred(List.of(f, o)).orElseThrow());
        assertSame(o, PricePrecedence.preferred(List.of(o, f)).orElseThrow());
        assertSame(f, PricePrecedence.preferred(List.of(f)).orElseThrow());
        assertEquals(Optional.empty(), PricePrecedence.preferred(List.of()));
        assertEquals(Optional.empty(), PricePrecedence.preferred(null));
        List<InstrumentPrice> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(f);
        assertSame(f, PricePrecedence.preferred(withNull).orElseThrow());
    }

    @Test
    void byInstrumentKeepsOneRowPerInstrument() {
        Instrument a = instrument();
        Instrument b = instrument();
        InstrumentPrice aFeed = feed(a, D2, "10");
        InstrumentPrice aOwn = own(a, D2, "11");
        InstrumentPrice bFeed = feed(b, D1, "20");
        Map<UUID, InstrumentPrice> map = PricePrecedence.byInstrument(List.of(aFeed, bFeed, aOwn));
        assertEquals(2, map.size());
        assertSame(aOwn, map.get(a.getId()));
        assertSame(bFeed, map.get(b.getId()));
        assertTrue(PricePrecedence.byInstrument(null).isEmpty());
    }

    @Test
    void collapseKeepsOrderAndPutsTheOwnRowInTheFeedRowsPlace() {
        Instrument i = instrument();
        InstrumentPrice f1 = feed(i, D1, "10");
        InstrumentPrice f2 = feed(i, D2, "11");
        InstrumentPrice o2 = own(i, D2, "15");
        assertEquals(List.of(f1, o2), PricePrecedence.collapse(List.of(f1, f2, o2)));
        assertEquals(List.of(o2, f1), PricePrecedence.collapse(List.of(o2, f2, f1)));
        assertEquals(List.of(), PricePrecedence.collapse(null));
        assertEquals(List.of(), PricePrecedence.collapse(List.of()));
    }

    @Test
    void ownManualIsOnlyTheUsersManualRow() {
        Instrument i = instrument();
        assertTrue(own(i, D1, "1").isOwnManual(user));
        assertEquals(false, own(i, D1, "1").isOwnManual(UUID.randomUUID()));
        assertEquals(false, own(i, D1, "1").isOwnManual(null));
        InstrumentPrice legacy = new InstrumentPrice(i, D1, BigDecimal.ONE, PriceSource.MANUAL);
        assertEquals(false, legacy.isOwnManual(user), "an ownerless MANUAL price is read-only");
        assertEquals(false, feed(i, D1, "1").isOwnManual(user));
    }
}
