package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CorporateActionSchemeChild;
import com.financeos.api.instrument.dto.CorporateActionSchemeDetails;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchemeDetailsLookupTest {

    private static final LocalDate EX = LocalDate.of(2025, 3, 10);

    private final SchemeEvidenceRepository evidence = mock(SchemeEvidenceRepository.class);
    private final SchemeEvidenceChildRepository children = mock(SchemeEvidenceChildRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final SchemeCheckRepository checks = mock(SchemeCheckRepository.class);
    private final SchemeDetailsLookup lookup = new SchemeDetailsLookup(evidence, children, instruments, checks, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());

    private static CorporateActionSuggestion demerger(String symbol, LocalDate ex) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setSymbol(symbol);
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(i);
        s.setKind(SuggestionKind.demerger);
        s.setExDate(ex);
        return s;
    }

    private static SchemeEvidence evidence(String symbol, String kind, LocalDate ex) {
        SchemeEvidence e = new SchemeEvidence();
        e.setId(UUID.randomUUID());
        e.setParentSymbol(symbol);
        e.setKind(kind);
        e.setExDate(ex);
        e.setCheckedAt(Instant.parse("2025-03-20T10:00:00Z"));
        return e;
    }

    private static SchemeEvidenceChild child(UUID evidenceId, int order, String symbol, String isin) {
        SchemeEvidenceChild c = new SchemeEvidenceChild();
        c.setEvidenceId(evidenceId);
        c.setChildOrder(order);
        c.setChildSymbol(symbol);
        c.setChildIsin(isin);
        c.setChildName(symbol + " Limited");
        c.setListedOn(EX.plusDays(40));
        return c;
    }

    @Test
    void fillsTheDetailsOfADemergerWithRatioCostAndSources() {
        CorporateActionSuggestion s = demerger("ACME", EX);
        SchemeEvidence e = evidence("ACME", "demerger", EX);
        e.setParentCostPct(new BigDecimal("86.5"));
        e.setCostKind("NOTICE");
        e.setCostUrl("https://nsearchives.example/c.pdf");
        e.setCostNoticeDate(LocalDate.of(2025, 3, 5));
        e.setCostQuote("Acme 86.5% Foods 13.5%");
        e.setMissing("RATIO_NOT_FOUND,NOTICE_SCANNED");
        SchemeEvidenceChild c = child(e.getId(), 0, "ACMEFOODS", "INE000B01011");
        c.setRatioFrom(10);
        c.setRatioTo(1);
        c.setRatioUrl("https://nsearchives.example/r.pdf");
        c.setRatioNoticeDate(LocalDate.of(2025, 3, 1));
        c.setRatioQuote("1 share for every 10");
        c.setChildCostPct(new BigDecimal("13.5"));
        when(evidence.findByKindAndSymbols(eq("demerger"), anyCollection())).thenReturn(List.of(e));
        when(children.findForEvidence(anyCollection())).thenReturn(List.of(c));
        UUID catalogId = UUID.randomUUID();
        Instrument catalog = new Instrument();
        catalog.setId(catalogId);
        catalog.setSymbol("ACMEFOODS");
        catalog.setExchange("NSE");
        catalog.setIsin("INE000B01011");
        when(instruments.findByIsinIn(anyCollection())).thenReturn(List.of(catalog));
        when(instruments.findByNseSymbolIn(anyCollection())).thenReturn(List.of());

        CorporateActionSchemeDetails d = lookup.forSuggestions(List.of(s)).get(s.getId());

        assertThat(d).isNotNull();
        assertThat(d.parentCostPct()).isEqualByComparingTo("86.5");
        assertThat(d.costKind()).isEqualTo("NOTICE");
        assertThat(d.costSource().url()).isEqualTo("https://nsearchives.example/c.pdf");
        assertThat(d.costSource().date()).isEqualTo(LocalDate.of(2025, 3, 5));
        assertThat(d.missing()).containsExactly("RATIO_NOT_FOUND", "NOTICE_SCANNED");
        assertThat(d.checkedAt()).isEqualTo(Instant.parse("2025-03-20T10:00:00Z"));
        assertThat(d.children()).hasSize(1);
        CorporateActionSchemeChild out = d.children().get(0);
        assertThat(out.symbol()).isEqualTo("ACMEFOODS");
        assertThat(out.yahooSymbol()).isEqualTo("ACMEFOODS.NS");
        assertThat(out.instrumentId()).isEqualTo(catalogId);
        assertThat(out.ratioFrom()).isEqualTo(10);
        assertThat(out.ratioTo()).isEqualTo(1);
        assertThat(out.ratioSource().quote()).isEqualTo("1 share for every 10");
        assertThat(out.childCostPct()).isEqualByComparingTo("13.5");
    }

    @Test
    void anNseRowWinsOverAnotherExchangesForTheSameIsin() {
        CorporateActionSuggestion s = demerger("ACME", EX);
        SchemeEvidence e = evidence("ACME", "demerger", EX);
        when(evidence.findByKindAndSymbols(eq("demerger"), anyCollection())).thenReturn(List.of(e));
        when(children.findForEvidence(anyCollection())).thenReturn(List.of(child(e.getId(), 0, "ACMEFOODS", "INE000B01011")));
        Instrument bse = catalog("INE000B01011", "BSE", "532000");
        Instrument nse = catalog("INE000B01011", "NSE", "ACMEFOODS");
        when(instruments.findByIsinIn(anyCollection())).thenReturn(List.of(bse, nse));
        when(instruments.findByNseSymbolIn(anyCollection())).thenReturn(List.of(nse));

        CorporateActionSchemeDetails d = lookup.forSuggestions(List.of(s)).get(s.getId());

        assertThat(d.children().get(0).instrumentId()).isEqualTo(nse.getId());
    }

    @Test
    void noReadingMeansNoDetailsAndOtherEventsOfTheSameStockDoNotMatch() {
        CorporateActionSuggestion s = demerger("ACME", EX);
        SchemeEvidence other = evidence("ACME", "demerger", EX.minusYears(2));
        when(evidence.findByKindAndSymbols(eq("demerger"), anyCollection())).thenReturn(List.of(other));
        when(children.findForEvidence(anyCollection())).thenReturn(List.of());

        assertThat(lookup.forSuggestions(List.of(s))).isEmpty();
        assertThat(lookup.forSuggestions(List.of())).isEmpty();
    }

    @Test
    void aWholePageIsOneEvidenceQueryOneChildQueryAndOneInstrumentLookup() {
        CorporateActionSuggestion a = demerger("AAA", EX);
        CorporateActionSuggestion b = demerger("BBB", EX);
        SchemeEvidence ea = evidence("AAA", "demerger", EX);
        SchemeEvidence eb = evidence("BBB", "demerger", EX);
        when(evidence.findByKindAndSymbols(eq("demerger"), anyCollection())).thenReturn(List.of(ea, eb));
        when(children.findForEvidence(anyCollection())).thenReturn(List.of(child(ea.getId(), 0, "AAAKID", null), child(eb.getId(), 0, "BBBKID", null)));
        when(instruments.findByNseSymbolIn(anyCollection())).thenReturn(List.of());

        Map<UUID, CorporateActionSchemeDetails> out = lookup.forSuggestions(List.of(a, b));

        assertThat(out).containsKeys(a.getId(), b.getId());
        assertThat(out.get(a.getId()).children().get(0).symbol()).isEqualTo("AAAKID");
        assertThat(out.get(b.getId()).children().get(0).symbol()).isEqualTo("BBBKID");
        verify(evidence, times(1)).findByKindAndSymbols(eq("demerger"), anyCollection());
        verify(children, times(1)).findForEvidence(anyCollection());
        verify(instruments, times(1)).findByNseSymbolIn(anyCollection());
    }

    @Test
    void stoppedStocksGetTheirMergerReadingBySymbol() {
        UUID instrumentId = UUID.randomUUID();
        SchemeEvidence e = evidence("GONE", "merger", null);
        e.setMissing(null);
        when(evidence.findByKindAndSymbols(eq("merger"), anyCollection())).thenReturn(List.of(e));
        SchemeEvidenceChild c = child(e.getId(), 0, "BUYER", "INE000C01011");
        c.setRatioFrom(5);
        c.setRatioTo(2);
        when(children.findForEvidence(anyCollection())).thenReturn(List.of(c));
        when(instruments.findByIsinIn(anyCollection())).thenReturn(List.of());
        when(instruments.findByNseSymbolIn(anyCollection())).thenReturn(List.of());

        CorporateActionSchemeDetails d = lookup.forStoppedStocks(Map.of(instrumentId, "gone")).get(instrumentId);

        assertThat(d).isNotNull();
        assertThat(d.children().get(0).ratioFrom()).isEqualTo(5);
        assertThat(d.children().get(0).ratioTo()).isEqualTo(2);
        assertThat(d.missing()).isEmpty();
        assertThat(d.costSource()).isNull();
    }

    private static Instrument catalog(String isin, String exchange, String symbol) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setIsin(isin);
        i.setExchange(exchange);
        i.setSymbol(symbol);
        return i;
    }
}
