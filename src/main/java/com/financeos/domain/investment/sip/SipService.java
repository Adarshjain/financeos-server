package com.financeos.domain.investment.sip;

import com.financeos.api.investment.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class SipService {

    private final SipRepository sipRepository;
    private final AccountRepository accountRepository;
    private final InstrumentRepository instrumentRepository;
    private final HoldingRepository holdingRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public SipService(SipRepository sipRepository,
                      AccountRepository accountRepository,
                      InstrumentRepository instrumentRepository,
                      HoldingRepository holdingRepository,
                      InvestmentTransactionRepository transactionRepository,
                      UserRepository userRepository) {
        this.sipRepository = sipRepository;
        this.accountRepository = accountRepository;
        this.instrumentRepository = instrumentRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    public SipResponse createSip(CreateSipRequest request) {
        Account brokerAccount = accountRepository.findById(request.brokerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.brokerAccountId()));

        if (brokerAccount.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        Instrument instrument = instrumentRepository.findById(request.instrumentId())
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", request.instrumentId()));

        UUID userId = UserContext.getCurrentUserId();
        User user = userId != null ? userRepository.getReferenceById(userId) : null;

        Sip sip = new Sip();
        sip.setUser(user);
        sip.setBrokerAccount(brokerAccount);
        sip.setInstrument(instrument);
        sip.setAmount(request.amount());
        sip.setFrequency(request.frequency());
        sip.setDayOfMonth(request.dayOfMonth());
        sip.setStartDate(request.startDate());
        sip.setEndDate(request.endDate());
        sip.setActive(request.active() != null ? request.active() : true);
        sip.setNotes(request.notes());

        Sip saved = sipRepository.save(sip);
        return SipResponse.from(saved, computeProgress(saved));
    }

    public SipResponse updateSip(UUID id, UpdateSipRequest request) {
        Sip sip = sipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sip", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (sip.getUser() == null || !sip.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Sip", id);
        }

        sip.setAmount(request.amount());
        sip.setFrequency(request.frequency());
        sip.setDayOfMonth(request.dayOfMonth());
        sip.setStartDate(request.startDate());
        sip.setEndDate(request.endDate());
        sip.setActive(request.active());
        sip.setNotes(request.notes());

        Sip saved = sipRepository.save(sip);
        return SipResponse.from(saved, computeProgress(saved));
    }

    public void deleteSip(UUID id) {
        Sip sip = sipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sip", id));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (sip.getUser() == null || !sip.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Sip", id);
        }

        sipRepository.delete(sip);
    }

    @Transactional(readOnly = true)
    public SipResponse getSip(UUID id) {
        Sip sip = sipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sip", id));
        return SipResponse.from(sip, computeProgress(sip));
    }

    @Transactional(readOnly = true)
    public List<SipResponse> getSips(UUID brokerAccountId, UUID instrumentId, Boolean active) {
        List<Sip> sips = sipRepository.findFilteredSips(brokerAccountId, instrumentId, active);
        return sips.stream().map(sip -> SipResponse.from(sip, computeProgress(sip))).toList();
    }

    /**
     * Recomputes progress dynamically from actual transactions (does not persist).
     * Execution attribution is by (broker, instrument) in [start_date, min(today, end_date)].
     */
    private SipProgressDto computeProgress(Sip sip) {
        LocalDate today = LocalDate.now();
        LocalDate cutoffDate = (sip.getEndDate() != null && sip.getEndDate().isBefore(today)) ? sip.getEndDate() : today;

        int expectedInstallments = 0;
        LocalDate d = sip.getStartDate();

        while (!d.isAfter(cutoffDate)) {
            expectedInstallments++;
            d = advanceDate(d, sip.getFrequency(), sip.getDayOfMonth());
        }

        LocalDate nextDueDate = null;
        if (sip.isActive()) {
            while (!d.isAfter(today)) {
                d = advanceDate(d, sip.getFrequency(), sip.getDayOfMonth());
            }
            if (sip.getEndDate() == null || !d.isAfter(sip.getEndDate())) {
                nextDueDate = d;
            }
        }

        // Query executed buys for holding (brokerAccount x instrument)
        int executedInstallments = 0;
        BigDecimal investedSoFar = BigDecimal.ZERO;
        BigDecimal unitsAccumulated = BigDecimal.ZERO;

        Optional<Holding> holdingOpt = holdingRepository.findByBrokerAccountIdAndInstrumentId(
                sip.getBrokerAccount().getId(), sip.getInstrument().getId());

        if (holdingOpt.isPresent()) {
            Page<InvestmentTransaction> txns = transactionRepository.findFilteredTransactions(
                    sip.getBrokerAccount().getId(), sip.getInstrument().getId(), null, Pageable.unpaged());

            for (InvestmentTransaction txn : txns.getContent()) {
                if (txn.getType() == InvestmentTransactionType.buy) {
                    LocalDate tDate = txn.getTradeDate();
                    if (!tDate.isBefore(sip.getStartDate()) && !tDate.isAfter(cutoffDate)) {
                        executedInstallments++;
                        BigDecimal txnCharges = txn.getTotalCharges() != null ? txn.getTotalCharges() : BigDecimal.ZERO;
                        BigDecimal cost = txn.getQuantity().multiply(txn.getPrice()).add(txnCharges);
                        investedSoFar = investedSoFar.add(cost);
                        unitsAccumulated = unitsAccumulated.add(txn.getQuantity());
                    }
                }
            }
        }

        BigDecimal avgCost = unitsAccumulated.compareTo(BigDecimal.ZERO) > 0
                ? investedSoFar.divide(unitsAccumulated, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        int missedInstallments = Math.max(0, expectedInstallments - executedInstallments);

        return new SipProgressDto(
                expectedInstallments,
                executedInstallments,
                missedInstallments,
                investedSoFar.setScale(2, RoundingMode.HALF_UP),
                unitsAccumulated.setScale(4, RoundingMode.HALF_UP),
                avgCost.setScale(4, RoundingMode.HALF_UP),
                nextDueDate
        );
    }

    private LocalDate advanceDate(LocalDate current, SipFrequency frequency, Integer dayOfMonth) {
        if (frequency == SipFrequency.weekly) {
            return current.plusWeeks(1);
        } else {
            LocalDate next = current.plusMonths(1);
            if (dayOfMonth != null && dayOfMonth >= 1 && dayOfMonth <= 31) {
                int targetDay = Math.min(dayOfMonth, next.lengthOfMonth());
                return next.withDayOfMonth(targetDay);
            }
            return next;
        }
    }
}
