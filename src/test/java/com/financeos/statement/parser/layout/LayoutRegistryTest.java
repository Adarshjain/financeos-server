package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Registry matching: order, issuer filter, required document text, header presence. */
class LayoutRegistryTest {

    private static final List<Line> DOC = List.of(
            ln(0, 50, "Statement", 40, "Period", 100),
            bankHeader(0, 100, 0),
            bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));

    @Test
    void theFirstMatchingProfileInListOrderWins() {
        LayoutProfile a = bank("a").build();
        LayoutProfile b = bank("b").build();
        assertSame(a, new LayoutRegistry(List.of(a, b)).match(DOC, null));
        assertSame(b, new LayoutRegistry(List.of(b, a)).match(DOC, null));
    }

    @Test
    void aProfileWhoseIssuerDiffersIsSkipped() {
        LayoutProfile axis = bank("axis-only").issuer("axis").build();
        LayoutProfile any = bank("any").build();
        LayoutRegistry reg = new LayoutRegistry(List.of(axis, any));
        assertSame(any, reg.match(DOC, "hdfc"));
        assertSame(any, reg.match(DOC, null));
        assertSame(axis, reg.match(DOC, "axis"));
        assertSame(axis, reg.match(DOC, "AXIS"));
    }

    @Test
    void aProfileWithoutAnIssuerMatchesAnyIssuer() {
        assertNotNull(new LayoutRegistry(List.of(bank("any").build())).match(DOC, "icici"));
    }

    @Test
    void noMatchWhenTheHeaderIsNotOnAnyPage() {
        List<Line> noHeader = List.of(bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        assertNull(new LayoutRegistry(List.of(bank("a").build())).match(noHeader, null));
    }

    @Test
    void noMatchWhenARequiredPhraseIsMissingAndAMatchWhenItIsThere() {
        LayoutRegistry reg = new LayoutRegistry(List.of(bank("req").requires("statement\\s+period").build()));
        assertNotNull(reg.match(DOC, null));
        assertNull(reg.match(List.of(bankHeader(0, 100, 0)), null));
    }

    @Test
    void aMatchOnTheSecondPageIsStillAMatch() {
        List<Line> lines = List.of(ln(0, 50, "Cover", 40), bankHeader(1, 60, 0));
        assertNotNull(new LayoutRegistry(List.of(bank("a").build())).match(lines, null));
    }

    @Test
    void emptyRegistryNeverMatches() {
        assertNull(new LayoutRegistry(List.of()).match(DOC, null));
    }

    @Test
    void theDefaultRegistryListsTheAxisProfile() {
        assertEquals("axis-card-dtda-2024", LayoutRegistry.defaults().profiles().get(0).id());
    }

    @Test
    void aProfileNeedsAHeaderAndConsistentColumns() {
        assertThrows(IllegalStateException.class, () -> LayoutProfile.builder("x")
                .product(LayoutProfile.Product.BANK).sign(LayoutProfile.Sign.MINUS_IS_CREDIT).build());
        assertThrows(IllegalStateException.class, () -> single("x").sign(LayoutProfile.Sign.SEPARATE_COLUMNS).build());
        assertThrows(IllegalStateException.class, () -> single("x").sign(LayoutProfile.Sign.BALANCE_DELTA).build());
        assertThrows(IllegalStateException.class, () -> single("x")
                .fromLabel(LayoutProfile.Role.IGNORE, "NOPE", 0, 10).sign(LayoutProfile.Sign.MINUS_IS_CREDIT).build());
    }
}
