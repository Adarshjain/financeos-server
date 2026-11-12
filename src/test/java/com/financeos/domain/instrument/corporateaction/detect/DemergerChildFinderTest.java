package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.instrument.corporateaction.detect.DemergerChildFinder.ChildCandidate;
import com.financeos.domain.instrument.corporateaction.detect.NseListingsClient.Listing;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemergerChildFinderTest {

    private final NseListingsClient client = mock(NseListingsClient.class);
    private final DemergerChildFinder finder = new DemergerChildFinder(client);

    private static Listing l(String symbol, String name, LocalDate d) {
        return new Listing(symbol, name, d, null);
    }

    private List<String> symbols(String name, String symbol, LocalDate ex) {
        return finder.find(name, symbol, ex).stream().map(ChildCandidate::symbol).toList();
    }

    @Test
    void itcHotelsIsTheChildOfItc() {
        when(client.listings()).thenReturn(List.of(
                l("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 1, 29)),
                l("ITC", "ITC Limited", LocalDate.of(1995, 1, 1)),
                l("OTHER", "Other Company Limited", LocalDate.of(2025, 1, 20))));
        assertThat(symbols("ITC Limited", "ITC", LocalDate.of(2025, 1, 6))).containsExactly("ITCHOTELS");
    }

    @Test
    void tataMotorsCommercialRanksFirstAndEarlierListingsAreOut() {
        when(client.listings()).thenReturn(List.of(
                l("TMPV", "Tata Motors Passenger Vehicles Limited", LocalDate.of(1998, 7, 22)),
                l("TATACAP", "Tata Capital Limited", LocalDate.of(2025, 10, 13)),
                l("TATAFUTURE", "Tata Industries Limited", LocalDate.of(2025, 12, 1)),
                l("TMCV", "Tata Motors Limited", LocalDate.of(2025, 11, 12))));
        List<String> out = symbols("Tata Motors Limited", "TATAMOTORS", LocalDate.of(2025, 10, 14));
        assertThat(out).containsExactly("TMCV");
    }

    @Test
    void windowIsAfterExDateAndUpTo180Days() {
        LocalDate ex = LocalDate.of(2025, 1, 6);
        when(client.listings()).thenReturn(List.of(
                l("ACMEA", "Acme A Limited", ex),
                l("ACMEB", "Acme B Limited", ex.plusDays(1)),
                l("ACMEC", "Acme C Limited", ex.plusDays(180)),
                l("ACMED", "Acme D Limited", ex.plusDays(181))));
        assertThat(symbols("Acme Limited", "ACME", ex)).containsExactly("ACMEB", "ACMEC");
    }

    @Test
    void fillerWordsAndPunctuationAreIgnored() {
        LocalDate ex = LocalDate.of(2025, 1, 6);
        when(client.listings()).thenReturn(List.of(
                l("LTDX", "Limited India Company Pvt Ltd", ex.plusDays(5)),
                l("ZEN", "Zenith Foods Ltd.", ex.plusDays(6))));
        // "The Zenith Co. Ltd" reduces to [zenith]; the filler-only name never matches
        assertThat(symbols("The Zenith Co. Ltd", "ZENITH", ex)).containsExactly("ZEN");
    }

    @Test
    void parentSymbolIsExcluded() {
        LocalDate ex = LocalDate.of(2025, 1, 6);
        when(client.listings()).thenReturn(List.of(l("ACME", "Acme Limited", ex.plusDays(3)),
                l("ACMEX", "Acme Spin Limited", ex.plusDays(4))));
        assertThat(symbols("Acme Limited", "ACME", ex)).containsExactly("ACMEX");
    }

    @Test
    void atMostThreeRankedBySharedWordsThenListingDate() {
        LocalDate ex = LocalDate.of(2025, 1, 6);
        when(client.listings()).thenReturn(List.of(
                l("A1", "Acme Retail Limited", ex.plusDays(10)),
                l("A2", "Acme Retail Foods Limited", ex.plusDays(30)),
                l("A3", "Acme One Limited", ex.plusDays(5)),
                l("A4", "Acme Two Limited", ex.plusDays(6)),
                l("A5", "Acme Retail Foods Limited", ex.plusDays(20))));
        assertThat(symbols("Acme Retail Foods Limited", "ACMERF", ex)).containsExactly("A5", "A2", "A1");
    }

    @Test
    void emptyListOrBlankNameGivesNothing() {
        when(client.listings()).thenReturn(List.of());
        assertThat(symbols("Acme Limited", "ACME", LocalDate.of(2025, 1, 6))).isEmpty();
        assertThat(symbols("Limited", null, LocalDate.of(2025, 1, 6))).isEmpty();
    }

    @Test
    void apostrophesAndSingleLetterTokensAreDropped() {
        LocalDate ex = LocalDate.of(2025, 1, 6);
        when(client.listings()).thenReturn(List.of(l("RDY", "Reddys Pharma Limited", ex.plusDays(9))));
        assertThat(symbols("Dr. Reddy's Labs Limited", "DRREDDY", ex)).isEmpty();
        assertThat(DemergerChildFinder.tokens("Reddy's A B Labs")).containsExactly("reddys", "labs");
        assertThat(symbols("Reddy's Limited", "RDYX", ex)).containsExactly("RDY");
    }

    @Test
    void twoWordParentNeedsTwoSharedWordsButOneWordParentNeedsOne() {
        LocalDate ex = LocalDate.of(2025, 10, 14);
        when(client.listings()).thenReturn(List.of(
                l("TATACAP", "Tata Capital Limited", LocalDate.of(2025, 11, 1)),
                l("TMCV", "Tata Motors Limited", LocalDate.of(2025, 11, 12))));
        assertThat(symbols("Tata Motors Limited", "TATAMOTORS", ex)).containsExactly("TMCV");
        when(client.listings()).thenReturn(List.of(l("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 11, 1))));
        assertThat(symbols("ITC Limited", "ITC", ex)).containsExactly("ITCHOTELS");
    }
}
