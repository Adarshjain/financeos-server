package com.financeos.domain.instrument;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.domain.instrument.price.AmfiFeedClient;
import com.financeos.domain.instrument.price.AmfiScheme;
import com.financeos.domain.instrument.search.AmfiInstrumentSearchProvider;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.instrument.search.YahooInstrumentSearchProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class IsinFirstSearchTest {

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private InstrumentAliasRepository aliasRepository;
    private InstrumentSearchService instrumentSearchService;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        aliasRepository = mock(InstrumentAliasRepository.class);

        instrumentSearchService = new InstrumentSearchService(
                instrumentRepository,
                priceRepository,
                aliasRepository,
                List.of()
        );
    }

    @Test
    void testAmfiProviderSearchByIsin() {
        AmfiFeedClient feedClient = mock(AmfiFeedClient.class);
        AmfiScheme scheme = new AmfiScheme("123456", "INF178L01001", "Nippon India Small Cap", new BigDecimal("150.00"), LocalDate.now());
        when(feedClient.all()).thenReturn(List.of(scheme));

        AmfiInstrumentSearchProvider provider = new AmfiInstrumentSearchProvider(feedClient);
        List<InstrumentCandidate> candidates = provider.search("INF178L01001", InstrumentType.mutual_fund);

        assertEquals(1, candidates.size());
        assertEquals("INF178L01001", candidates.get(0).isin());
        assertEquals("Nippon India Small Cap", candidates.get(0).name());
    }

    @Test
    void testIsinAuthoritativeRefreshAndAliasCreation() {
        Instrument existing = new Instrument();
        existing.setId(UUID.randomUUID());
        existing.setIsin("INE758T01015");
        existing.setSymbol("ZOMATO");
        existing.setName("Zomato Limited");
        existing.setType(InstrumentType.stock);

        when(instrumentRepository.findByIsin("INE758T01015")).thenReturn(Optional.of(existing));
        when(instrumentRepository.save(any(Instrument.class))).thenAnswer(i -> i.getArgument(0));

        ResolveInstrumentRequest req = new ResolveInstrumentRequest(
                InstrumentType.stock,
                "Eternal Limited",
                "ETERNAL",
                "NSE",
                "INE758T01015",
                null,
                "ETERNAL.NS",
                "INR",
                null
        );

        InstrumentResponse response = instrumentSearchService.resolve(req);

        assertEquals("ETERNAL", response.symbol());
        assertEquals("Eternal Limited", response.name());
        assertEquals("ETERNAL.NS", response.yahooSymbol());

        // Verify alias saved for old symbol ZOMATO
        verify(aliasRepository, times(1)).save(argThat(alias ->
                "ZOMATO".equals(alias.getOldSymbol()) && "Zomato Limited".equals(alias.getOldName())
        ));
    }
}
