package com.financeos.domain.lending;

import com.financeos.api.lending.dto.*;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class LendingService {

    private static final Logger log = LoggerFactory.getLogger(LendingService.class);

    private final CounterpartyRepository counterpartyRepository;
    private final LendingRepository lendingRepository;
    private final LendingRepaymentRepository lendingRepaymentRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceValidator transactionValidator;

    public LendingService(
            CounterpartyRepository counterpartyRepository,
            LendingRepository lendingRepository,
            LendingRepaymentRepository lendingRepaymentRepository,
            UserRepository userRepository,
            TransactionReferenceValidator transactionValidator) {
        this.counterpartyRepository = counterpartyRepository;
        this.lendingRepository = lendingRepository;
        this.lendingRepaymentRepository = lendingRepaymentRepository;
        this.userRepository = userRepository;
        this.transactionValidator = transactionValidator;
    }

    // --- Counterparty Management ---

    public CounterpartyResponse createCounterparty(CreateCounterpartyRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ValidationException("Counterparty name is required");
        }
        if (counterpartyRepository.existsByName(req.name())) {
            throw new ValidationException("Counterparty with name '" + req.name() + "' already exists");
        }

        Counterparty cp = new Counterparty();
        cp.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
        cp.setName(req.name());
        cp.setNotes(req.notes());

        Counterparty saved = counterpartyRepository.save(cp);
        return CounterpartyResponse.from(saved, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    @Transactional(readOnly = true)
    public Page<CounterpartyResponse> getCounterparties(Pageable pageable) {
        Page<Counterparty> page = counterpartyRepository.findAll(pageable);
        return page.map(this::toCounterpartyResponse);
    }

    public CounterpartyResponse updateCounterparty(UUID id, UpdateCounterpartyRequest req) {
        Counterparty cp = getCounterpartyAndVerifyOwnership(id);
        if (req.name() != null && !req.name().isBlank() && !req.name().equalsIgnoreCase(cp.getName())) {
            if (counterpartyRepository.existsByName(req.name())) {
                throw new ValidationException("Counterparty with name '" + req.name() + "' already exists");
            }
            cp.setName(req.name());
        }
        if (req.notes() != null) {
            cp.setNotes(req.notes());
        }
        Counterparty saved = counterpartyRepository.save(cp);
        return toCounterpartyResponse(saved);
    }

    public void deleteCounterparty(UUID id) {
        Counterparty cp = getCounterpartyAndVerifyOwnership(id);
        if (lendingRepository.existsByCounterparty_Id(id)) {
            throw new ValidationException("Cannot delete counterparty while lendings exist for this person");
        }
        counterpartyRepository.delete(cp);
    }

    // --- Lendings Management ---

    public LendingResponse createLending(CreateLendingRequest req) {
        if ((req.counterpartyId() == null && (req.newCounterpartyName() == null || req.newCounterpartyName().isBlank()))
                || (req.counterpartyId() != null && req.newCounterpartyName() != null && !req.newCounterpartyName().isBlank())) {
            throw new ValidationException("Specify exactly one of counterpartyId or newCounterpartyName");
        }

        Counterparty cp;
        if (req.counterpartyId() != null) {
            cp = getCounterpartyAndVerifyOwnership(req.counterpartyId());
        } else {
            String name = req.newCounterpartyName().trim();
            cp = counterpartyRepository.findByName(name)
                    .orElseGet(() -> {
                        Counterparty newCp = new Counterparty();
                        newCp.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
                        newCp.setName(name);
                        return counterpartyRepository.save(newCp);
                    });
        }

        Transaction transaction = transactionValidator.validateAndGetTransaction(req.transactionId());

        Lending lending = new Lending();
        lending.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
        lending.setCounterparty(cp);
        lending.setDirection(req.direction());
        lending.setAmount(req.amount());
        lending.setLendDate(req.lendDate());
        lending.setExpectedReturnDate(req.expectedReturnDate());
        lending.setStatus(LendingStatus.outstanding);
        lending.setTransaction(transaction);
        lending.setNotes(req.notes());

        Lending saved = lendingRepository.save(lending);
        return LendingResponse.from(saved, BigDecimal.ZERO, Collections.emptyList());
    }

    @Transactional(readOnly = true)
    public Page<LendingResponse> getLendings(UUID counterpartyId, LendingStatus status, Pageable pageable) {
        Page<Lending> lendings;
        if (counterpartyId != null && status != null) {
            lendings = lendingRepository.findByCounterparty_IdAndStatus(counterpartyId, status, pageable);
        } else if (counterpartyId != null) {
            lendings = lendingRepository.findByCounterparty_Id(counterpartyId, pageable);
        } else if (status != null) {
            lendings = lendingRepository.findByStatus(status, pageable);
        } else {
            lendings = lendingRepository.findAll(pageable);
        }

        return lendings.map(this::toLendingResponse);
    }

    @Transactional(readOnly = true)
    public LendingResponse getLendingDetail(UUID lendingId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        return toLendingResponse(lending);
    }

    public LendingResponse updateLending(UUID lendingId, UpdateLendingRequest req) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);

        List<LendingRepayment> repayments = lendingRepaymentRepository.findByLending_IdOrderByDateAscCreatedAtAsc(lendingId);

        boolean coreChanged = (req.direction() != null && req.direction() != lending.getDirection())
                || (req.amount() != null && req.amount().compareTo(lending.getAmount()) != 0)
                || (req.lendDate() != null && !req.lendDate().equals(lending.getLendDate()));

        if (coreChanged && !repayments.isEmpty()) {
            throw new ValidationException("Direction, amount, and lendDate can only be edited while zero repayments exist");
        }

        if (req.expectedReturnDate() != null) lending.setExpectedReturnDate(req.expectedReturnDate());
        if (req.notes() != null) lending.setNotes(req.notes());

        if (coreChanged) {
            if (req.direction() != null) lending.setDirection(req.direction());
            if (req.amount() != null) lending.setAmount(req.amount());
            if (req.lendDate() != null) lending.setLendDate(req.lendDate());
        }

        updateLendingStatus(lending, getRepaidTotal(lendingId));
        Lending saved = lendingRepository.save(lending);
        return toLendingResponse(saved);
    }

    public void deleteLending(UUID lendingId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        lendingRepository.delete(lending);
    }

    public LendingRepaymentResponse addRepayment(UUID lendingId, CreateLendingRepaymentRequest req) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        if (lending.getStatus() == LendingStatus.written_off) {
            throw new ValidationException("Cannot add repayment to a written_off lending");
        }

        if (req.date().isBefore(lending.getLendDate())) {
            throw new ValidationException("Repayment date (" + req.date() + ") cannot be before lend date (" + lending.getLendDate() + ")");
        }

        BigDecimal currentRepaid = getRepaidTotal(lendingId);
        BigDecimal outstanding = lending.getAmount().subtract(currentRepaid).max(BigDecimal.ZERO);

        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0 || req.amount().compareTo(outstanding) > 0) {
            throw new ValidationException("Repayment amount must be > 0 and <= current outstanding (" + outstanding + ")");
        }

        Transaction transaction = transactionValidator.validateAndGetTransaction(req.transactionId());

        LendingRepayment repayment = new LendingRepayment();
        repayment.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
        repayment.setLending(lending);
        repayment.setAmount(req.amount());
        repayment.setDate(req.date());
        repayment.setTransaction(transaction);

        LendingRepayment saved = lendingRepaymentRepository.save(repayment);

        updateLendingStatus(lending, currentRepaid.add(req.amount()));
        lendingRepository.save(lending);

        return LendingRepaymentResponse.from(saved);
    }

    public void deleteRepayment(UUID lendingId, UUID repaymentId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        LendingRepayment repayment = lendingRepaymentRepository.findById(repaymentId)
                .orElseThrow(() -> new ResourceNotFoundException("LendingRepayment", repaymentId));

        if (!repayment.getLending().getId().equals(lendingId)) {
            throw new ValidationException("Repayment does not belong to lending " + lendingId);
        }

        lendingRepaymentRepository.delete(repayment);
        lendingRepaymentRepository.flush();

        if (lending.getStatus() != LendingStatus.written_off) {
            updateLendingStatus(lending, getRepaidTotal(lendingId));
            lendingRepository.save(lending);
        }
    }

    public void writeOffLending(UUID lendingId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        if (lending.getStatus() == LendingStatus.settled) {
            throw new ValidationException("Cannot write off a fully settled lending");
        }
        lending.setStatus(LendingStatus.written_off);
        lendingRepository.save(lending);
    }

    public void reopenLending(UUID lendingId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        lending.setStatus(LendingStatus.outstanding);
        updateLendingStatus(lending, getRepaidTotal(lendingId));
        lendingRepository.save(lending);
    }

    private CounterpartyResponse toCounterpartyResponse(Counterparty cp) {
        List<Lending> lendings = lendingRepository.findByCounterparty_Id(cp.getId());
        BigDecimal lent = BigDecimal.ZERO;
        BigDecimal borrowed = BigDecimal.ZERO;
        long openCount = 0;

        for (Lending l : lendings) {
            if (l.getStatus() != LendingStatus.settled && l.getStatus() != LendingStatus.written_off) {
                openCount++;
                BigDecimal repaid = getRepaidTotal(l.getId());
                BigDecimal rem = l.getAmount().subtract(repaid).max(BigDecimal.ZERO);
                if (l.getDirection() == LendingDirection.lent) {
                    lent = lent.add(rem);
                } else {
                    borrowed = borrowed.add(rem);
                }
            }
        }
        return CounterpartyResponse.from(cp, lent, borrowed, openCount);
    }

    // --- Aggregates & Helper Methods ---

    @Transactional(readOnly = true)
    public LendingTotals getLendingTotals() {
        List<Lending> activeLendings = lendingRepository.findByStatusIn(List.of(LendingStatus.outstanding, LendingStatus.partially_repaid));
        BigDecimal lent = BigDecimal.ZERO;
        BigDecimal borrowed = BigDecimal.ZERO;

        for (Lending l : activeLendings) {
            BigDecimal repaid = getRepaidTotal(l.getId());
            BigDecimal rem = l.getAmount().subtract(repaid).max(BigDecimal.ZERO);
            if (l.getDirection() == LendingDirection.lent) {
                lent = lent.add(rem);
            } else {
                borrowed = borrowed.add(rem);
            }
        }
        return new LendingTotals(lent.setScale(2, RoundingMode.HALF_UP), borrowed.setScale(2, RoundingMode.HALF_UP), lent.subtract(borrowed).setScale(2, RoundingMode.HALF_UP));
    }

    public record LendingTotals(BigDecimal lentOutstanding, BigDecimal borrowedOutstanding, BigDecimal netReceivable) {}

    @Transactional(readOnly = true)
    public List<ObligationItemDto> getUpcomingLendingObligations(LocalDate startDate, LocalDate endDate) {
        List<Lending> activeLendings = lendingRepository.findByStatusIn(List.of(LendingStatus.outstanding, LendingStatus.partially_repaid));
        List<ObligationItemDto> items = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Lending l : activeLendings) {
            if (l.getExpectedReturnDate() != null) {
                LocalDate date = l.getExpectedReturnDate();
                boolean isOverdue = date.isBefore(today);
                boolean isUpcoming = !date.isBefore(today) && !date.isAfter(endDate);

                if (isOverdue || isUpcoming) {
                    BigDecimal repaid = getRepaidTotal(l.getId());
                    BigDecimal rem = l.getAmount().subtract(repaid).max(BigDecimal.ZERO);
                    items.add(new ObligationItemDto(
                            "lending_due",
                            date,
                            rem.setScale(2, RoundingMode.HALF_UP),
                            isOverdue ? "overdue" : "upcoming",
                            null,
                            null,
                            null,
                            l.getId(),
                            l.getCounterparty().getId(),
                            l.getCounterparty().getName(),
                            l.getDirection()
                    ));
                }
            }
        }
        return items;
    }

    private LendingResponse toLendingResponse(Lending lending) {
        List<LendingRepayment> repayments = lendingRepaymentRepository.findByLending_IdOrderByDateAscCreatedAtAsc(lending.getId());
        BigDecimal repaidTotal = repayments.stream()
                .map(LendingRepayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LendingRepaymentResponse> repaymentResponses = repayments.stream()
                .map(LendingRepaymentResponse::from)
                .toList();

        return LendingResponse.from(lending, repaidTotal, repaymentResponses);
    }

    private BigDecimal getRepaidTotal(UUID lendingId) {
        List<LendingRepayment> repayments = lendingRepaymentRepository.findByLending_IdOrderByDateAscCreatedAtAsc(lendingId);
        return repayments.stream()
                .map(LendingRepayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void updateLendingStatus(Lending lending, BigDecimal repaid) {
        if (lending.getStatus() == LendingStatus.written_off) {
            return;
        }
        if (repaid.compareTo(BigDecimal.ZERO) == 0) {
            lending.setStatus(LendingStatus.outstanding);
        } else if (repaid.compareTo(lending.getAmount()) >= 0) {
            lending.setStatus(LendingStatus.settled);
        } else {
            lending.setStatus(LendingStatus.partially_repaid);
        }
    }

    private Counterparty getCounterpartyAndVerifyOwnership(UUID cpId) {
        Counterparty cp = counterpartyRepository.findById(cpId)
                .orElseThrow(() -> new ResourceNotFoundException("Counterparty", cpId));
        UUID userId = UserContext.getCurrentUserId();
        if (cp.getUser() == null || !cp.getUser().getId().equals(userId)) {
            log.warn("Security Breach Attempt: User {} tried to access foreign counterparty {}", userId, cpId);
            throw new ValidationException("You do not have permission to access counterparty " + cpId);
        }
        return cp;
    }

    private Lending getLendingAndVerifyOwnership(UUID lendingId) {
        Lending lending = lendingRepository.findById(lendingId)
                .orElseThrow(() -> new ResourceNotFoundException("Lending", lendingId));
        UUID userId = UserContext.getCurrentUserId();
        if (lending.getUser() == null || !lending.getUser().getId().equals(userId)) {
            log.warn("Security Breach Attempt: User {} tried to access foreign lending {}", userId, lendingId);
            throw new ValidationException("You do not have permission to access lending " + lendingId);
        }
        return lending;
    }
}
