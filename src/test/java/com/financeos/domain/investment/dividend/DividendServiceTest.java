package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.AcceptSuggestionsRequest;
import com.financeos.api.investment.dto.AcceptSuggestionsResponse;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.price.YahooDividendEventsClient;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DividendServiceTest {

    private DividendRepository dividendRepository;
    private HoldingRepository holdingRepository;
    private UserRepository userRepository;
    private InvestmentService investmentService;
    private YahooDividendEventsClient yahooClient;
    private InvestmentTransactionRepository transactionRepository;
    private DividendService dividendService;

    @BeforeEach
    void setUp() {
        dividendRepository = Mockito.mock(DividendRepository.class);
        holdingRepository = Mockito.mock(HoldingRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        investmentService = Mockito.mock(InvestmentService.class);
        yahooClient = Mockito.mock(YahooDividendEventsClient.class);
        transactionRepository = Mockito.mock(InvestmentTransactionRepository.class);

        dividendService = new DividendService(
                dividendRepository, holdingRepository, userRepository,
                investmentService, yahooClient, transactionRepository
        );
    }

    @Test
    void testAcceptSuggestionsCreatesSuggestedRows() {
        UUID holdingId = UUID.randomUUID();
        Holding mockHolding = Mockito.mock(Holding.class);
        Instrument mockInst = Mockito.mock(Instrument.class);
        com.financeos.domain.account.Account mockBrokerAcc = Mockito.mock(com.financeos.domain.account.Account.class);
        when(mockHolding.getId()).thenReturn(holdingId);
        when(mockHolding.getInstrument()).thenReturn(mockInst);
        when(mockHolding.getBrokerAccount()).thenReturn(mockBrokerAcc);
        when(mockBrokerAcc.getId()).thenReturn(UUID.randomUUID());
        when(mockInst.getId()).thenReturn(UUID.randomUUID());
        when(mockInst.getName()).thenReturn("Test Stock");

        when(holdingRepository.findById(holdingId)).thenReturn(Optional.of(mockHolding));
        when(dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holdingId)).thenReturn(List.of());

        Dividend savedDiv = new Dividend();
        savedDiv.setId(UUID.randomUUID());
        savedDiv.setHolding(mockHolding);
        savedDiv.setType(DividendType.dividend);
        savedDiv.setAmount(new BigDecimal("150.00"));
        savedDiv.setPerUnit(new BigDecimal("15.00"));
        savedDiv.setExDate(LocalDate.of(2026, 2, 1));
        savedDiv.setPayDate(LocalDate.of(2026, 2, 5));
        savedDiv.setSource("suggested");

        when(dividendRepository.save(any(Dividend.class))).thenAnswer(invocation -> {
            Dividend divToSave = invocation.getArgument(0);
            divToSave.setId(UUID.randomUUID());
            return divToSave;
        });

        AcceptSuggestionsRequest request = new AcceptSuggestionsRequest(List.of(
                new AcceptSuggestionsRequest.Item(
                        holdingId,
                        LocalDate.of(2026, 2, 1),
                        LocalDate.of(2026, 2, 5),
                        new BigDecimal("150.00"),
                        new BigDecimal("15.00"),
                        "Yahoo auto-detected"
                )
        ));

        AcceptSuggestionsResponse response = dividendService.acceptSuggestions(request);

        assertEquals(0, response.skippedCount());
        assertEquals(1, response.created().size());
        assertEquals("suggested", response.created().get(0).source());
        assertEquals(new BigDecimal("150.00"), response.created().get(0).amount());
    }
}
