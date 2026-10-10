package com.financeos.domain.instrument;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One user's overrides applied over the shared catalog, field by field. */
class InstrumentOverridesTest {

    private final UUID user = UUID.randomUUID();

    private static Instrument instrument(InstrumentType type, String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(type);
        i.setName(name);
        i.setSymbol("CAT");
        i.setExchange("NSE");
        i.setCurrency("INR");
        return i;
    }

    private UserInstrumentOverride row(Instrument i) {
        return new UserInstrumentOverride(user, i.getId());
    }

    @Test
    void noneAppliesTheCatalog() {
        Instrument i = instrument(InstrumentType.stock, "Infosys");
        InstrumentOverrides none = InstrumentOverrides.NONE;
        assertTrue(none.isEmpty());
        assertEquals("Infosys", none.name(i));
        assertEquals("CAT", none.symbol(i));
        assertEquals("NSE", none.exchange(i));
        assertEquals("INR", none.currency(i));
        assertEquals(InstrumentType.stock, none.type(i));
        assertNull(none.assetClass(i.getId()));
        assertEquals(List.of(), none.overriddenFields(i.getId()));
        assertNull(none.get(null));
    }

    @Test
    void eachOverriddenFieldReplacesTheCatalogValueAndTheRestStay() {
        Instrument i = instrument(InstrumentType.stock, "Infosys");
        UserInstrumentOverride r = row(i);
        r.setName("My Infy");
        r.setCurrency("USD");
        InstrumentOverrides o = InstrumentOverrides.of(List.of(r));

        assertEquals("My Infy", o.name(i));
        assertEquals("CAT", o.symbol(i), "not overridden");
        assertEquals("NSE", o.exchange(i), "not overridden");
        assertEquals("USD", o.currency(i));
        assertEquals(InstrumentType.stock, o.type(i));
        assertEquals(List.of(InstrumentOverrides.NAME, InstrumentOverrides.CURRENCY), o.overriddenFields(i.getId()));
        assertEquals(Set.of(i.getId()), o.instrumentIds());
    }

    @Test
    void everyFieldIsReportedInAStableOrder() {
        Instrument i = instrument(InstrumentType.etf, "Gold BeES");
        UserInstrumentOverride r = row(i);
        r.setAssetClass(AssetClass.GOLD);
        r.setType(InstrumentType.mutual_fund);
        r.setCurrency("USD");
        r.setExchange("BSE");
        r.setSymbol("GLD");
        r.setName("Gold");
        InstrumentOverrides o = InstrumentOverrides.of(List.of(r));
        assertEquals(List.of("name", "symbol", "exchange", "currency", "type", "assetClass"), o.overriddenFields(i.getId()));
        assertEquals("GLD", o.symbol(i));
        assertEquals("BSE", o.exchange(i));
        assertEquals(InstrumentType.mutual_fund, o.type(i));
        assertEquals(Map.of(i.getId(), AssetClass.GOLD), o.assetClasses());
    }

    @Test
    void aMissingCatalogCurrencyReadsInr() {
        Instrument i = instrument(InstrumentType.stock, "X");
        i.setCurrency(null);
        assertEquals("INR", InstrumentOverrides.NONE.currency(i));
    }

    @Test
    void emptyRowsAndNullsAreIgnored() {
        Instrument i = instrument(InstrumentType.stock, "X");
        List<UserInstrumentOverride> rows = new ArrayList<>();
        rows.add(row(i));
        rows.add(null);
        assertSame(InstrumentOverrides.NONE, InstrumentOverrides.of(rows));
        assertSame(InstrumentOverrides.NONE, InstrumentOverrides.of(null));
        assertSame(InstrumentOverrides.NONE, InstrumentOverrides.of(List.of()));
        assertSame(InstrumentOverrides.NONE, InstrumentOverrides.orNone(null));
        InstrumentOverrides some = InstrumentOverrides.of(List.of(new UserInstrumentOverride(user, i.getId(), AssetClass.DEBT)));
        assertSame(some, InstrumentOverrides.orNone(some));
        assertFalse(some.isEmpty());
    }

    @Test
    void theClassificationFollowsTheUsersAssetClassThenTheirType() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "HDFC Liquid Fund");
        UserInstrumentOverride asStock = row(fund);
        asStock.setType(InstrumentType.stock);
        AssetClassifier.Classification retyped = InstrumentOverrides.of(List.of(asStock)).classification(fund);
        assertEquals(AssetClass.EQUITY, retyped.assetClass(), "a stock is equity by rule");
        assertEquals(TaxClass.EQUITY_ORIENTED, retyped.taxClass());

        asStock.setAssetClass(AssetClass.GOLD);
        AssetClassifier.Classification pinned = InstrumentOverrides.of(List.of(asStock)).classification(fund);
        assertEquals(AssetClass.GOLD, pinned.assetClass(), "the pinned class wins over the type's");
        assertEquals(TaxClass.EQUITY_ORIENTED, pinned.taxClass(), "a stock is always equity-oriented for tax");

        assertEquals(AssetClassifier.effective(fund), InstrumentOverrides.NONE.classification(fund));
    }
}
