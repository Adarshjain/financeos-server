package com.financeos.domain.investment;

import com.financeos.api.investment.dto.CreateInvestmentTransactionRequest;
import com.financeos.api.investment.dto.InvestmentPositionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class InvestmentService {

    private final InvestmentTransactionRepository investmentTransactionRepository;
    private final AccountRepository accountRepository;

    public InvestmentService(InvestmentTransactionRepository investmentTransactionRepository,
            AccountRepository accountRepository) {
        this.investmentTransactionRepository = investmentTransactionRepository;
        this.accountRepository = accountRepository;
    }

    public InvestmentTransaction createTransaction(CreateInvestmentTransactionRequest request) {
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));

        if (account.getType() != AccountType.stock && account.getType() != AccountType.mutual_fund) {
            throw new ValidationException("Investment transactions can only be added to stock or mutual fund accounts");
        }

        // Validate sell doesn't exceed holdings
        if (request.type() == InvestmentTransactionType.sell) {
            BigDecimal currentQuantity = calculateCurrentQuantity(request.accountId());
            if (request.quantity().compareTo(currentQuantity) > 0) {
                throw new ValidationException("Cannot sell more than current holdings. Available: " + currentQuantity);
            }
        }

        InvestmentTransaction transaction = new InvestmentTransaction(
                account,
                request.type(),
                request.quantity(),
                request.price(),
                request.date());
        transaction.setUser(account.getUser());
        transaction.setMetadata(request.metadata());

        return investmentTransactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public Page<InvestmentTransaction> getAllTransactions(Pageable pageable) {
        return investmentTransactionRepository.findAllOrdered(pageable);
    }

    @Transactional(readOnly = true)
    public InvestmentPositionResponse getPositions() {
        List<UUID> accountIds = investmentTransactionRepository.findDistinctAccountIds();
        List<InvestmentPositionResponse.Position> positions = new ArrayList<>();

        for (UUID accountId : accountIds) {
            Account account = accountRepository.findById(accountId).orElse(null);
            if (account == null)
                continue;

            FifoResult fifoResult = calculateFifoPosition(accountId);

            if (fifoResult.quantity.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // Skip accounts with no holdings
            }

            BigDecimal lastTradedPrice = getLastTradedPrice(account);
            BigDecimal currentValue = lastTradedPrice != null
                    ? fifoResult.quantity.multiply(lastTradedPrice)
                    : null;
            BigDecimal unrealizedGainLoss = currentValue != null
                    ? currentValue.subtract(fifoResult.totalCost)
                    : null;
            BigDecimal unrealizedGainLossPercent = unrealizedGainLoss != null
                    && fifoResult.totalCost.compareTo(BigDecimal.ZERO) > 0
                            ? unrealizedGainLoss.divide(fifoResult.totalCost, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                            : null;

            positions.add(new InvestmentPositionResponse.Position(
                    accountId,
                    getInstrumentCode(account),
                    account.getName(),
                    fifoResult.quantity,
                    fifoResult.averageCost,
                    fifoResult.totalCost,
                    lastTradedPrice,
                    currentValue,
                    unrealizedGainLoss,
                    unrealizedGainLossPercent));
        }

        return new InvestmentPositionResponse(positions);
    }

    private BigDecimal calculateCurrentQuantity(UUID accountId) {
        List<InvestmentTransaction> transactions = investmentTransactionRepository
                .findByAccountIdOrderByDateAsc(accountId);

        BigDecimal quantity = BigDecimal.ZERO;
        for (InvestmentTransaction tx : transactions) {
            if (tx.getType() == InvestmentTransactionType.buy) {
                quantity = quantity.add(tx.getQuantity());
            } else {
                quantity = quantity.subtract(tx.getQuantity());
            }
        }
        return quantity;
    }

    /**
     * Calculate position using FIFO (First In, First Out) method.
     * Each sell reduces holdings starting from the earliest buys.
     */
    private FifoResult calculateFifoPosition(UUID accountId) {
        List<InvestmentTransaction> transactions = investmentTransactionRepository
                .findByAccountIdOrderByDateAsc(accountId);

        // Track lots: each buy creates a lot with quantity and cost
        List<Lot> lots = new ArrayList<>();

        for (InvestmentTransaction tx : transactions) {
            if (tx.getType() == InvestmentTransactionType.buy) {
                lots.add(new Lot(tx.getQuantity(), tx.getPrice()));
            } else {
                // FIFO: sell from oldest lots first
                BigDecimal remainingToSell = tx.getQuantity();
                while (remainingToSell.compareTo(BigDecimal.ZERO) > 0 && !lots.isEmpty()) {
                    Lot oldestLot = lots.get(0);
                    if (oldestLot.quantity.compareTo(remainingToSell) <= 0) {
                        remainingToSell = remainingToSell.subtract(oldestLot.quantity);
                        lots.remove(0);
                    } else {
                        oldestLot.quantity = oldestLot.quantity.subtract(remainingToSell);
                        remainingToSell = BigDecimal.ZERO;
                    }
                }
            }
        }

        // Calculate totals from remaining lots
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (Lot lot : lots) {
            totalQuantity = totalQuantity.add(lot.quantity);
            totalCost = totalCost.add(lot.quantity.multiply(lot.costPerUnit));
        }

        BigDecimal averageCost = totalQuantity.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalQuantity, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new FifoResult(totalQuantity, totalCost, averageCost);
    }

    private BigDecimal getLastTradedPrice(Account account) {
        if (account.getStockDetails() != null) {
            return account.getStockDetails().getLastTradedPrice();
        }
        if (account.getMutualFundDetails() != null) {
            return account.getMutualFundDetails().getLastTradedPrice();
        }
        return null;
    }

    private String getInstrumentCode(Account account) {
        if (account.getStockDetails() != null) {
            return account.getStockDetails().getInstrumentCode();
        }
        if (account.getMutualFundDetails() != null) {
            return account.getMutualFundDetails().getInstrumentCode();
        }
        return null;
    }

    private static class Lot {
        BigDecimal quantity;
        BigDecimal costPerUnit;

        Lot(BigDecimal quantity, BigDecimal costPerUnit) {
            this.quantity = quantity;
            this.costPerUnit = costPerUnit;
        }
    }

    private record FifoResult(BigDecimal quantity, BigDecimal totalCost, BigDecimal averageCost) {
    }
}
