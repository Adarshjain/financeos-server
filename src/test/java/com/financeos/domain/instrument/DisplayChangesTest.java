package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Which display fields an edit changed, against how the editing user saw the instrument. */
class DisplayChangesTest {

    private final UUID user = UUID.randomUUID();

    private Instrument instrument() {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.mutual_fund);
        i.setName("Axis Bluechip");
        i.setSymbol("AXBC");
        i.setExchange("NSE");
        i.setCurrency("INR");
        return i;
    }

    @Test
    void valuesAsTheUserSawThemAreNoChangeEvenWhenTheyAreTheirOverrides() {
        Instrument i = instrument();
        UserInstrumentOverride row = new UserInstrumentOverride(user, i.getId());
        row.setName("My Fund");
        row.setType(InstrumentType.etf);
        InstrumentOverrides view = InstrumentOverrides.of(List.of(row));

        DisplayChanges changes = DisplayChanges.between(view, i,
                new InstrumentRequest(InstrumentType.etf, " My Fund ", "axbc", "nse", null, null, null, "inr"));

        assertEquals(DisplayChanges.NONE, changes);
        assertFalse(changes.any());
    }

    @Test
    void eachEditedFieldCountsAndABlankCurrencyIsNone() {
        Instrument i = instrument();
        DisplayChanges changes = DisplayChanges.between(InstrumentOverrides.NONE, i,
                new InstrumentRequest(InstrumentType.stock, "axis bluechip", "", "BSE", null, null, null, " "));
        assertEquals(new DisplayChanges(true, true, true, false, true), changes,
                "name is case-sensitive, a cleared symbol is a change, a blank currency is not");
    }
}
