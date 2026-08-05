package com.financeos.domain.investment;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.OptionType;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class InvestmentServiceFnoTest {

    @Test
    void testCalculateHoldingPositionForFnoLongOption() {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.option);
        instrument.setName("NIFTY24AUG24500CE");
        instrument.setTradingSymbol("NIFTY24AUG24500CE");
        instrument.setUnderlyingSymbol("NIFTY");
        instrument.setOptionType(OptionType.CE);
        instrument.setStrikePrice(new BigDecimal("24500"));

        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setType(AccountType.broker);
        account.setName("Zerodha");

        Holding holding = new Holding(account, instrument, null);
        holding.setId(UUID.randomUUID());

        InvestmentTransaction buy = new InvestmentTransaction();
        buy.setType(InvestmentTransactionType.buy);
        buy.setQuantity(new BigDecimal("50"));
        buy.setPrice(new BigDecimal("100.00"));
        buy.setTotalCharges(new BigDecimal("10.00"));

        InvestmentTransaction sell = new InvestmentTransaction();
        sell.setType(InvestmentTransactionType.sell);
        sell.setQuantity(new BigDecimal("50"));
        sell.setPrice(new BigDecimal("150.00"));
        sell.setTotalCharges(new BigDecimal("15.00"));

        InvestmentTransactionRepository txnRepo = new DummyTxnRepo(holding.getId(), List.of(buy, sell));

        InvestmentService investmentService = new InvestmentService(
                txnRepo, null, null, null, null, null, null, null, null, null
        );

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);

        assertNotNull(pos);
        // buyValue = 5000, sellValue = 7500, charges = 25
        // realized = 7500 - 5000 - 25 = 2475
        assertEquals(new BigDecimal("2475.00"), pos.realized());
        assertEquals(new BigDecimal("25.00"), pos.totalCharges());
        assertEquals(BigDecimal.ZERO, pos.openQty());
        assertFalse(pos.unclosed());
        assertNull(pos.currentValue());
    }

    @Test
    void testCalculateHoldingPositionForShortOption() {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.option);
        instrument.setTradingSymbol("NIFTY24AUG24500PE");

        Holding holding = new Holding(new Account(), instrument, null);
        holding.setId(UUID.randomUUID());

        InvestmentTransaction sellFirst = new InvestmentTransaction();
        sellFirst.setType(InvestmentTransactionType.sell);
        sellFirst.setQuantity(new BigDecimal("50"));
        sellFirst.setPrice(new BigDecimal("120.00"));
        sellFirst.setTotalCharges(new BigDecimal("10.00"));

        InvestmentTransaction buyBack = new InvestmentTransaction();
        buyBack.setType(InvestmentTransactionType.buy);
        buyBack.setQuantity(new BigDecimal("50"));
        buyBack.setPrice(new BigDecimal("30.00"));
        buyBack.setTotalCharges(new BigDecimal("10.00"));

        InvestmentTransactionRepository txnRepo = new DummyTxnRepo(holding.getId(), List.of(sellFirst, buyBack));

        InvestmentService investmentService = new InvestmentService(
                txnRepo, null, null, null, null, null, null, null, null, null
        );

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);

        // buyValue = 1500, sellValue = 6000, charges = 20
        // realized = 6000 - 1500 - 20 = 4480
        assertEquals(new BigDecimal("4480.00"), pos.realized());
        assertEquals(BigDecimal.ZERO, pos.openQty());
        assertFalse(pos.unclosed());
    }

    private static class DummyTxnRepo implements InvestmentTransactionRepository {
        private final UUID holdingId;
        private final List<InvestmentTransaction> txns;

        public DummyTxnRepo(UUID holdingId, List<InvestmentTransaction> txns) {
            this.holdingId = holdingId;
            this.txns = txns;
        }

        @Override
        public List<InvestmentTransaction> findByHoldingIdOrderByTradeDateAscCreatedAtAsc(UUID id) {
            if (holdingId.equals(id)) return txns;
            return List.of();
        }

        @Override
        public Page<InvestmentTransaction> findFilteredTransactions(UUID brokerAccountId, UUID instrumentId, UUID holdingId, String search, Pageable pageable) {
            return Page.empty();
        }

        @Override public void flush() {}
        @Override public <S extends InvestmentTransaction> S saveAndFlush(S entity) { return null; }
        @Override public <S extends InvestmentTransaction> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<InvestmentTransaction> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) {}
        @Override public void deleteAllInBatch() {}
        @Override public InvestmentTransaction getOne(UUID uuid) { return null; }
        @Override public InvestmentTransaction getById(UUID uuid) { return null; }
        @Override public InvestmentTransaction getReferenceById(UUID uuid) { return null; }
        @Override public <S extends InvestmentTransaction> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends InvestmentTransaction> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends InvestmentTransaction> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public List<InvestmentTransaction> findAll() { return List.of(); }
        @Override public List<InvestmentTransaction> findAllById(Iterable<UUID> uuids) { return List.of(); }
        @Override public <S extends InvestmentTransaction> S save(S entity) { return null; }
        @Override public Optional<InvestmentTransaction> findById(UUID uuid) { return Optional.empty(); }
        @Override public boolean existsById(UUID uuid) { return false; }
        @Override public long count() { return 0; }
        @Override public void deleteById(UUID uuid) {}
        @Override public void delete(InvestmentTransaction entity) {}
        @Override public void deleteAllById(Iterable<? extends UUID> uuids) {}
        @Override public void deleteAll(Iterable<? extends InvestmentTransaction> entities) {}
        @Override public void deleteAll() {}
        @Override public List<InvestmentTransaction> findAll(Sort sort) { return List.of(); }
        @Override public Page<InvestmentTransaction> findAll(Pageable pageable) { return null; }
        @Override public <S extends InvestmentTransaction> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends InvestmentTransaction> Page<S> findAll(Example<S> example, Pageable pageable) { return null; }
        @Override public <S extends InvestmentTransaction> long count(Example<S> example) { return 0; }
        @Override public <S extends InvestmentTransaction> boolean exists(Example<S> example) { return false; }
        @Override public <S extends InvestmentTransaction, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
    }
}
