package com.financeos.domain.investment.fno;

import com.financeos.api.investment.dto.CreateFnoTradeRequest;
import com.financeos.api.investment.dto.FnoTradeResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FnoTradeServiceTest {

    private FnoTradeRepository fnoTradeRepository;
    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private FnoTradeService fnoTradeService;

    private UUID userId;
    private User user;
    private UUID brokerAccountId;
    private Account brokerAccount;

    @BeforeEach
    void setUp() {
        fnoTradeRepository = mock(FnoTradeRepository.class);
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        fnoTradeService = new FnoTradeService(fnoTradeRepository, accountRepository, userRepository);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setEmail("test@example.com");

        brokerAccountId = UUID.randomUUID();
        brokerAccount = new Account();
        brokerAccount.setId(brokerAccountId);
        brokerAccount.setType(AccountType.broker);
        brokerAccount.setName("Zerodha");
    }

    @Test
    void testCreateFnoTradeSuccessAndRealizedPnlCalculation() {
        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(brokerAccount));
        when(userRepository.getReferenceById(any())).thenReturn(user);
        when(fnoTradeRepository.save(any(FnoTrade.class))).thenAnswer(i -> {
            FnoTrade t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        CreateFnoTradeRequest req = new CreateFnoTradeRequest(
                brokerAccountId,
                "NIFTY24AUG24500CE",
                "NIFTY",
                FnoContractType.option,
                OptionType.CE,
                new BigDecimal("24500"),
                LocalDate.of(2024, 8, 29),
                new BigDecimal("50"),
                new BigDecimal("5000.00"),
                new BigDecimal("7500.00"),
                new BigDecimal("25.00"),
                LocalDate.of(2024, 8, 1),
                LocalDate.of(2024, 8, 2),
                "Manual trade"
        );

        FnoTradeResponse res = fnoTradeService.createTrade(req);

        assertNotNull(res);
        assertEquals("NIFTY24AUG24500CE", res.tradingSymbol());
        assertEquals("NIFTY", res.underlyingSymbol());
        assertEquals(FnoContractType.option, res.contractType());
        // realizedPnl = 7500 - 5000 - 25 = 2475
        assertEquals(new BigDecimal("2475.00"), res.realizedPnl());

        ArgumentCaptor<FnoTrade> captor = ArgumentCaptor.forClass(FnoTrade.class);
        verify(fnoTradeRepository).save(captor.capture());
        FnoTrade saved = captor.getValue();
        assertEquals("manual", saved.getSource());
    }

    @Test
    void testCreateFnoTradeShortOptionSuccess() {
        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(brokerAccount));
        when(userRepository.getReferenceById(any())).thenReturn(user);
        when(fnoTradeRepository.save(any(FnoTrade.class))).thenAnswer(i -> {
            FnoTrade t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        // Short position: sold at 6000, bought back at 1500, charges 20 => realized = 6000 - 1500 - 20 = 4480
        CreateFnoTradeRequest req = new CreateFnoTradeRequest(
                brokerAccountId,
                "NIFTY24AUG24500PE",
                "NIFTY",
                FnoContractType.option,
                OptionType.PE,
                new BigDecimal("24500"),
                LocalDate.of(2024, 8, 29),
                new BigDecimal("50"),
                new BigDecimal("1500.00"),
                new BigDecimal("6000.00"),
                new BigDecimal("20.00"),
                LocalDate.of(2024, 8, 1),
                LocalDate.of(2024, 8, 2),
                "Short option"
        );

        FnoTradeResponse res = fnoTradeService.createTrade(req);

        assertNotNull(res);
        assertEquals(new BigDecimal("4480.00"), res.realizedPnl());
    }

    @Test
    void testCreateFnoTradeNonBrokerAccountThrowsException() {
        Account bankAccount = new Account();
        bankAccount.setId(brokerAccountId);
        bankAccount.setType(AccountType.bank_account);

        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(bankAccount));

        CreateFnoTradeRequest req = new CreateFnoTradeRequest(
                brokerAccountId,
                "RELIANCE24AUGFUT",
                "RELIANCE",
                FnoContractType.future,
                null,
                null,
                LocalDate.of(2024, 8, 29),
                new BigDecimal("250"),
                new BigDecimal("50000.00"),
                new BigDecimal("45000.00"),
                new BigDecimal("100.00"),
                null, null, null
        );

        assertThrows(ValidationException.class, () -> fnoTradeService.createTrade(req));
    }

    @Test
    void testDeleteTradeSuccess() {
        UUID tradeId = UUID.randomUUID();
        FnoTrade trade = new FnoTrade();
        trade.setId(tradeId);

        when(fnoTradeRepository.findById(tradeId)).thenReturn(Optional.of(trade));

        fnoTradeService.deleteTrade(tradeId);

        verify(fnoTradeRepository).delete(trade);
    }

    @Test
    void testDeleteTradeNotFoundThrowsException() {
        UUID tradeId = UUID.randomUUID();
        when(fnoTradeRepository.findById(tradeId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> fnoTradeService.deleteTrade(tradeId));
    }

    @Test
    void testListTradesSuccess() {
        FnoTrade trade = new FnoTrade();
        trade.setId(UUID.randomUUID());
        trade.setTradingSymbol("NIFTY24AUG24500CE");
        trade.setRealizedPnl(new BigDecimal("1000.00"));

        when(fnoTradeRepository.findAllWithBrokerAccount()).thenReturn(java.util.List.of(trade));
        when(fnoTradeRepository.sumRealizedPnl()).thenReturn(new BigDecimal("1000.00"));

        com.financeos.api.investment.dto.FnoTradeListResponse res = fnoTradeService.listTrades();

        assertNotNull(res);
        assertEquals(1, res.trades().size());
        assertEquals(new BigDecimal("1000.00"), res.totalRealizedPnl());
    }
}
