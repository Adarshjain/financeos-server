package com.financeos.domain.investment.dividend;

import com.financeos.api.investment.dto.CreateDividendRequest;
import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.api.investment.dto.UpdateDividendRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class DividendService {

    private final DividendRepository dividendRepository;
    private final HoldingRepository holdingRepository;
    private final UserRepository userRepository;

    public DividendService(DividendRepository dividendRepository,
                           HoldingRepository holdingRepository,
                           UserRepository userRepository) {
        this.dividendRepository = dividendRepository;
        this.holdingRepository = holdingRepository;
        this.userRepository = userRepository;
    }

    public DividendResponse createDividend(CreateDividendRequest request) {
        Holding holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(request.brokerAccountId(), request.instrumentId())
                .orElseThrow(() -> new ValidationException("No holding found for broker account " + request.brokerAccountId() + " and instrument " + request.instrumentId()));

        UUID userId = UserContext.getCurrentUserId();
        User user = userId != null ? userRepository.getReferenceById(userId) : null;

        Dividend dividend = new Dividend();
        dividend.setUser(user);
        dividend.setHolding(holding);
        dividend.setType(request.type());
        dividend.setAmount(request.amount());
        dividend.setPerUnit(request.perUnit());
        dividend.setTds(request.tds());
        dividend.setExDate(request.exDate());
        dividend.setPayDate(request.payDate());
        dividend.setNotes(request.notes());

        Dividend saved = dividendRepository.save(dividend);
        return DividendResponse.from(saved);
    }

    public DividendResponse updateDividend(UUID id, UpdateDividendRequest request) {
        Dividend dividend = dividendRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dividend", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (dividend.getUser() == null || !dividend.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Dividend", id);
        }

        dividend.setType(request.type());
        dividend.setAmount(request.amount());
        dividend.setPerUnit(request.perUnit());
        dividend.setTds(request.tds());
        dividend.setExDate(request.exDate());
        dividend.setPayDate(request.payDate());
        dividend.setNotes(request.notes());

        Dividend saved = dividendRepository.save(dividend);
        return DividendResponse.from(saved);
    }

    public void deleteDividend(UUID id) {
        Dividend dividend = dividendRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dividend", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (dividend.getUser() == null || !dividend.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Dividend", id);
        }

        dividendRepository.delete(dividend);
    }

    @Transactional(readOnly = true)
    public Page<DividendResponse> getDividends(UUID holdingId, UUID brokerAccountId, UUID instrumentId, Pageable pageable) {
        Page<Dividend> page = dividendRepository.findFilteredDividends(holdingId, brokerAccountId, instrumentId, pageable);
        return page.map(DividendResponse::from);
    }
}
