package com.financeos.domain.investment.fno;

import com.financeos.api.investment.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.instrument.FnoSymbolParser;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class FnoTradeService {

    private final FnoTradeRepository fnoTradeRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public FnoTradeService(
            FnoTradeRepository fnoTradeRepository,
            AccountRepository accountRepository,
            UserRepository userRepository
    ) {
        this.fnoTradeRepository = fnoTradeRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    public FnoTradeResponse createTrade(CreateFnoTradeRequest request) {
        Account account = accountRepository.findById(request.brokerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.brokerAccountId()));

        if (account.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userId != null ? userRepository.getReferenceById(userId) : null;

        FnoTrade trade = new FnoTrade();
        trade.setUser(user);
        trade.setBrokerAccount(account);
        trade.setTradingSymbol(request.tradingSymbol());

        // Parse trading symbol if details aren't explicitly provided
        FnoSymbolParser.FnoParsedContract parsed = FnoSymbolParser.parse(request.tradingSymbol());
        trade.setUnderlyingSymbol(request.underlyingSymbol() != null ? request.underlyingSymbol() : parsed.underlyingSymbol());
        trade.setContractType(request.contractType() != null ? request.contractType() : parsed.contractType());
        trade.setOptionType(request.optionType() != null ? request.optionType() : parsed.optionType());
        trade.setStrikePrice(request.strikePrice() != null ? request.strikePrice() : parsed.strikePrice());
        trade.setExpiryDate(request.expiryDate() != null ? request.expiryDate() : parsed.expiryDate());

        trade.setQuantity(request.quantity());
        trade.setBuyValue(request.buyValue());
        trade.setSellValue(request.sellValue());

        BigDecimal charges = request.totalCharges() != null ? request.totalCharges() : BigDecimal.ZERO;
        trade.setTotalCharges(charges);
        trade.setRealizedPnl(request.sellValue().subtract(request.buyValue()).subtract(charges));

        trade.setEntryDate(request.entryDate());
        trade.setExitDate(request.exitDate());
        trade.setSource("manual");
        trade.setNotes(request.notes());

        FnoTrade saved = fnoTradeRepository.save(trade);
        return FnoTradeResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public FnoTradeListResponse listTrades() {
        List<FnoTrade> trades = fnoTradeRepository.findAllWithBrokerAccount();
        List<FnoTradeResponse> responses = trades.stream()
                .map(FnoTradeResponse::from)
                .toList();
        BigDecimal totalRealized = fnoTradeRepository.sumRealizedPnl();
        return new FnoTradeListResponse(responses, totalRealized != null ? totalRealized : BigDecimal.ZERO);
    }

    public void deleteTrade(UUID id) {
        FnoTrade trade = fnoTradeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FnoTrade", id));
        fnoTradeRepository.delete(trade);
    }

    public FnoTradeResponse updateTrade(UUID id, CreateFnoTradeRequest request) {
        FnoTrade trade = fnoTradeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FnoTrade", id));

        Account account = accountRepository.findById(request.brokerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.brokerAccountId()));

        if (account.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        trade.setBrokerAccount(account);
        trade.setTradingSymbol(request.tradingSymbol());

        FnoSymbolParser.FnoParsedContract parsed = FnoSymbolParser.parse(request.tradingSymbol());
        trade.setUnderlyingSymbol(request.underlyingSymbol() != null ? request.underlyingSymbol() : parsed.underlyingSymbol());
        trade.setContractType(request.contractType() != null ? request.contractType() : parsed.contractType());
        trade.setOptionType(request.optionType() != null ? request.optionType() : parsed.optionType());
        trade.setStrikePrice(request.strikePrice() != null ? request.strikePrice() : parsed.strikePrice());
        trade.setExpiryDate(request.expiryDate() != null ? request.expiryDate() : parsed.expiryDate());

        trade.setQuantity(request.quantity());
        trade.setBuyValue(request.buyValue());
        trade.setSellValue(request.sellValue());

        BigDecimal charges = request.totalCharges() != null ? request.totalCharges() : BigDecimal.ZERO;
        trade.setTotalCharges(charges);
        trade.setRealizedPnl(request.sellValue().subtract(request.buyValue()).subtract(charges));

        trade.setEntryDate(request.entryDate());
        trade.setExitDate(request.exitDate());
        trade.setNotes(request.notes());

        FnoTrade saved = fnoTradeRepository.save(trade);
        return FnoTradeResponse.from(saved);
    }

    public List<FnoTrade> importTrades(UUID brokerAccountId, List<CommitFnoTradeDto> commitTrades) {
        if (commitTrades == null || commitTrades.isEmpty()) {
            return List.of();
        }

        Account account = accountRepository.findById(brokerAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", brokerAccountId));

        UUID userId = UserContext.getCurrentUserId();
        User user = userId != null ? userRepository.getReferenceById(userId) : null;

        List<FnoTrade> savedTrades = new ArrayList<>();

        for (CommitFnoTradeDto dto : commitTrades) {
            if (dto.skip()) {
                continue;
            }

            if (dto.externalRef() != null && !dto.externalRef().isBlank() &&
                    fnoTradeRepository.existsByBrokerAccountIdAndExternalRef(brokerAccountId, dto.externalRef())) {
                continue;
            }

            FnoTrade trade = new FnoTrade();
            trade.setUser(user);
            trade.setBrokerAccount(account);
            trade.setTradingSymbol(dto.tradingSymbol());
            trade.setUnderlyingSymbol(dto.underlyingSymbol());
            trade.setContractType(dto.contractType() != null ? dto.contractType() : FnoContractType.future);
            trade.setOptionType(dto.optionType());
            trade.setStrikePrice(dto.strikePrice());
            trade.setExpiryDate(dto.expiryDate());

            trade.setQuantity(dto.quantity());
            trade.setBuyValue(dto.buyValue());
            trade.setSellValue(dto.sellValue());

            BigDecimal charges = dto.totalCharges() != null ? dto.totalCharges() : BigDecimal.ZERO;
            trade.setTotalCharges(charges);
            trade.setRealizedPnl(dto.sellValue().subtract(dto.buyValue()).subtract(charges));

            trade.setEntryDate(dto.entryDate());
            trade.setExitDate(dto.exitDate());
            trade.setSource("import");
            trade.setExternalRef(dto.externalRef());

            savedTrades.add(fnoTradeRepository.save(trade));
        }

        return savedTrades;
    }
}
