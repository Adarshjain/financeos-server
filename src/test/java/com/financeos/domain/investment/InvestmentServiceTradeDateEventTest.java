package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Editing a trade's date publishes the price-refresh event (drives the price-history window); other edits do not. */
class InvestmentServiceTradeDateEventTest {

    private final InvestmentTransactionRepository transactions = mock(InvestmentTransactionRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private InvestmentService service;
    private InvestmentTransaction txn;
    private UUID instrumentId;
    private LocalDate oldDate;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        instrumentId = UUID.randomUUID();
        Instrument instrument = new Instrument();
        instrument.setId(instrumentId);
        Holding holding = new Holding();
        holding.setId(UUID.randomUUID());
        holding.setInstrument(instrument);
        com.financeos.domain.account.Account account = new com.financeos.domain.account.Account();
        account.setId(UUID.randomUUID());
        holding.setBrokerAccount(account);
        oldDate = LocalDate.of(2023, 5, 10);
        txn = new InvestmentTransaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setHolding(holding);
        txn.setTradeDate(oldDate);
        when(transactions.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactions.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any())).thenReturn(List.of());
        TradeSettlementClassificationRepository classifications = mock(TradeSettlementClassificationRepository.class);
        when(classifications.findByBrokerAccountIdAndInstrumentIdAndTradeDate(any(), any(), any())).thenReturn(Optional.empty());
        service = spy(new InvestmentService(transactions, null, null, null, null, null, null, null, classifications, publisher, null));
        doReturn(null).when(service).calculateHoldingPosition(any(Holding.class));
    }

    @AfterEach
    void tearDown() {
        UserContext.setCurrentUserId(null);
    }

    private com.financeos.api.investment.dto.UpdateInvestmentTransactionRequest request(LocalDate date) {
        return new com.financeos.api.investment.dto.UpdateInvestmentTransactionRequest(
                InvestmentTransactionType.buy, null, BigDecimal.TEN, BigDecimal.ONE, date, null, null);
    }

    @Test
    void changingTheTradeDateToAnEarlierDatePublishesTheEvent() {
        service.updateTransaction(txn.getId(), request(oldDate.minusMonths(6)));
        ArgumentCaptor<PriceRefreshEvent> event = ArgumentCaptor.forClass(PriceRefreshEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals(java.util.Set.of(instrumentId), event.getValue().instrumentIds());
    }

    @Test
    void changingTheTradeDateToALaterDatePublishesTheEvent() {
        service.updateTransaction(txn.getId(), request(oldDate.plusMonths(2)));
        verify(publisher).publishEvent(any(PriceRefreshEvent.class));
    }

    @Test
    void editingWithoutChangingTheDateDoesNotPublish() {
        service.updateTransaction(txn.getId(), request(oldDate));
        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
