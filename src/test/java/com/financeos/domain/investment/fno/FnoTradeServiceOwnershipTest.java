package com.financeos.domain.investment.fno;

import com.financeos.api.investment.dto.CommitFnoTradeDto;
import com.financeos.api.investment.dto.CreateFnoTradeRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An F&amp;O trade, and the broker account a trade is created on, moved to or imported into, belong to
 * the signed-in user: another user's trade id or broker account id reads as not found, exactly like a
 * missing one, and nothing is changed.
 */
class FnoTradeServiceOwnershipTest {

    private FnoTradeRepository fnoTradeRepository;
    private AccountRepository accountRepository;
    private FnoTradeService fnoTradeService;

    private final UUID me = UUID.randomUUID();
    private final UUID someoneElse = UUID.randomUUID();
    private Account myBroker;

    @BeforeEach
    void setUp() {
        fnoTradeRepository = mock(FnoTradeRepository.class);
        accountRepository = mock(AccountRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.getReferenceById(me)).thenReturn(user(me));
        fnoTradeService = new FnoTradeService(fnoTradeRepository, accountRepository, userRepository);

        myBroker = account(AccountType.broker);
        when(accountRepository.findByIdAndUser_Id(myBroker.getId(), me)).thenReturn(Optional.of(myBroker));
        when(fnoTradeRepository.save(any(FnoTrade.class))).thenAnswer(i -> i.getArgument(0));
        UserContext.setCurrentUserId(me);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteTrade_anotherUsersTradeIsNotFoundAndNotDeleted() {
        FnoTrade theirs = trade(someoneElse);
        when(fnoTradeRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertThrows(ResourceNotFoundException.class, () -> fnoTradeService.deleteTrade(theirs.getId()));

        verify(fnoTradeRepository, never()).delete(any());
    }

    @Test
    void deleteTrade_aTradeWithoutAnOwnerIsNotFoundAndNotDeleted() {
        FnoTrade ownerless = trade(null);
        when(fnoTradeRepository.findById(ownerless.getId())).thenReturn(Optional.of(ownerless));

        assertThrows(ResourceNotFoundException.class, () -> fnoTradeService.deleteTrade(ownerless.getId()));

        verify(fnoTradeRepository, never()).delete(any());
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateTrade_myTradeOnMyBrokerAccountIsSaved() {
        FnoTrade mine = trade(me);
        when(fnoTradeRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

        fnoTradeService.updateTrade(mine.getId(), request(myBroker.getId()));

        verify(fnoTradeRepository).save(mine);
        assertSame(myBroker, mine.getBrokerAccount());
        assertEquals(new BigDecimal("2475.00"), mine.getRealizedPnl());
    }

    @Test
    void updateTrade_anotherUsersTradeIsNotFoundAndUnchanged() {
        FnoTrade theirs = trade(someoneElse);
        Account theirBroker = theirs.getBrokerAccount();
        when(fnoTradeRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertThrows(ResourceNotFoundException.class,
                () -> fnoTradeService.updateTrade(theirs.getId(), request(myBroker.getId())));

        verify(fnoTradeRepository, never()).save(any());
        assertSame(theirBroker, theirs.getBrokerAccount());
        assertEquals("OLD", theirs.getTradingSymbol());
    }

    @Test
    void updateTrade_movingMyTradeToAnotherUsersBrokerAccountIsNotFound() {
        FnoTrade mine = trade(me);
        Account before = mine.getBrokerAccount();
        UUID theirAccount = UUID.randomUUID();
        when(fnoTradeRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(accountRepository.findByIdAndUser_Id(theirAccount, me)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> fnoTradeService.updateTrade(mine.getId(), request(theirAccount)));

        verify(fnoTradeRepository, never()).save(any());
        assertSame(before, mine.getBrokerAccount());
    }

    @Test
    void updateTrade_movingMyTradeToMyNonBrokerAccountIsRejected() {
        FnoTrade mine = trade(me);
        Account myBank = account(AccountType.bank_account);
        when(fnoTradeRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(accountRepository.findByIdAndUser_Id(myBank.getId(), me)).thenReturn(Optional.of(myBank));

        assertThrows(ValidationException.class, () -> fnoTradeService.updateTrade(mine.getId(), request(myBank.getId())));

        verify(fnoTradeRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- create

    @Test
    void createTrade_onAnotherUsersBrokerAccountIsNotFoundAndSavesNothing() {
        UUID theirAccount = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(theirAccount, me)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> fnoTradeService.createTrade(request(theirAccount)));

        verify(accountRepository).findByIdAndUser_Id(theirAccount, me);
        verify(fnoTradeRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- import

    @Test
    void importTrades_intoMyBrokerAccountSavesThem() {
        List<FnoTrade> saved = fnoTradeService.importTrades(myBroker.getId(), List.of(commitRow()));

        assertEquals(1, saved.size());
        assertSame(myBroker, saved.get(0).getBrokerAccount());
        assertEquals(me, saved.get(0).getUser().getId());
    }

    @Test
    void importTrades_intoAnotherUsersBrokerAccountIsNotFoundAndSavesNothing() {
        UUID theirAccount = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(theirAccount, me)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> fnoTradeService.importTrades(theirAccount, List.of(commitRow())));

        verify(fnoTradeRepository, never()).save(any());
    }

    @Test
    void importTrades_intoMyNonBrokerAccountIsRejected() {
        Account myBank = account(AccountType.bank_account);
        when(accountRepository.findByIdAndUser_Id(myBank.getId(), me)).thenReturn(Optional.of(myBank));

        assertThrows(ValidationException.class, () -> fnoTradeService.importTrades(myBank.getId(), List.of(commitRow())));

        verify(fnoTradeRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- fixtures

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Account account(AccountType type) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setType(type);
        account.setName(type.name());
        return account;
    }

    private static FnoTrade trade(UUID ownerId) {
        FnoTrade trade = new FnoTrade();
        trade.setId(UUID.randomUUID());
        if (ownerId != null) {
            trade.setUser(user(ownerId));
        }
        trade.setBrokerAccount(account(AccountType.broker));
        trade.setTradingSymbol("OLD");
        return trade;
    }

    private static CreateFnoTradeRequest request(UUID brokerAccountId) {
        return new CreateFnoTradeRequest(brokerAccountId, "NIFTY24AUG24500CE", "NIFTY", FnoContractType.option,
                com.financeos.domain.instrument.OptionType.CE, new BigDecimal("24500"), LocalDate.of(2024, 8, 29),
                new BigDecimal("50"), new BigDecimal("5000.00"), new BigDecimal("7500.00"), new BigDecimal("25.00"),
                LocalDate.of(2024, 8, 1), LocalDate.of(2024, 8, 2), null);
    }

    private static CommitFnoTradeDto commitRow() {
        return new CommitFnoTradeDto("NIFTY24AUG24500CE", "NIFTY", FnoContractType.option, null, null, null,
                new BigDecimal("50"), new BigDecimal("5000.00"), new BigDecimal("7500.00"), null,
                LocalDate.of(2024, 8, 1), null, null, false);
    }
}
