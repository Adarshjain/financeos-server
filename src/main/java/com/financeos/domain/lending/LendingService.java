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
    private final UserRepository userRepository;
    private final TransactionReferenceValidator transactionValidator;

    public LendingService(
            CounterpartyRepository counterpartyRepository,
            LendingRepository lendingRepository,
            UserRepository userRepository,
            TransactionReferenceValidator transactionValidator) {
        this.counterpartyRepository = counterpartyRepository;
        this.lendingRepository = lendingRepository;
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
        lending.setEntryDate(req.entryDate());
        lending.setExpectedReturnDate(req.expectedReturnDate());
        lending.setTransaction(transaction);
        lending.setNotes(req.notes());

        Lending saved = lendingRepository.save(lending);
        return LendingResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<LendingResponse> getLendings(UUID counterpartyId, Pageable pageable) {
        Page<Lending> lendings;
        if (counterpartyId != null) {
            lendings = lendingRepository.findByCounterparty_Id(counterpartyId, pageable);
        } else {
            lendings = lendingRepository.findAll(pageable);
        }
        return lendings.map(LendingResponse::from);
    }

    @Transactional(readOnly = true)
    public LendingResponse getLendingDetail(UUID lendingId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        return LendingResponse.from(lending);
    }

    public LendingResponse updateLending(UUID lendingId, UpdateLendingRequest req) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);

        if (req.direction() != null) lending.setDirection(req.direction());
        if (req.amount() != null) lending.setAmount(req.amount());
        if (req.entryDate() != null) lending.setEntryDate(req.entryDate());
        if (req.expectedReturnDate() != null) lending.setExpectedReturnDate(req.expectedReturnDate());
        if (req.notes() != null) lending.setNotes(req.notes());

        Lending saved = lendingRepository.save(lending);
        return LendingResponse.from(saved);
    }

    public void deleteLending(UUID lendingId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        lendingRepository.delete(lending);
    }

    private CounterpartyResponse toCounterpartyResponse(Counterparty cp) {
        List<Lending> entries = lendingRepository.findByCounterparty_Id(cp.getId());
        BigDecimal totalLent = BigDecimal.ZERO;
        BigDecimal totalBorrowed = BigDecimal.ZERO;

        for (Lending l : entries) {
            if (l.getDirection() == LendingDirection.lent) {
                totalLent = totalLent.add(l.getAmount());
            } else if (l.getDirection() == LendingDirection.borrowed) {
                totalBorrowed = totalBorrowed.add(l.getAmount());
            }
        }
        return CounterpartyResponse.from(cp, totalLent, totalBorrowed, entries.size());
    }

    // --- Aggregates & Helper Methods ---

    @Transactional(readOnly = true)
    public LendingTotals getLendingTotals() {
        List<Counterparty> counterparties = counterpartyRepository.findAll();
        BigDecimal lentOutstanding = BigDecimal.ZERO;
        BigDecimal borrowedOutstanding = BigDecimal.ZERO;

        for (Counterparty cp : counterparties) {
            List<Lending> entries = lendingRepository.findByCounterparty_Id(cp.getId());
            BigDecimal cpLent = BigDecimal.ZERO;
            BigDecimal cpBorrowed = BigDecimal.ZERO;

            for (Lending l : entries) {
                if (l.getDirection() == LendingDirection.lent) {
                    cpLent = cpLent.add(l.getAmount());
                } else if (l.getDirection() == LendingDirection.borrowed) {
                    cpBorrowed = cpBorrowed.add(l.getAmount());
                }
            }

            BigDecimal net = cpLent.subtract(cpBorrowed);
            if (net.compareTo(BigDecimal.ZERO) > 0) {
                lentOutstanding = lentOutstanding.add(net);
            } else if (net.compareTo(BigDecimal.ZERO) < 0) {
                borrowedOutstanding = borrowedOutstanding.add(net.abs());
            }
        }

        BigDecimal netReceivable = lentOutstanding.subtract(borrowedOutstanding);
        return new LendingTotals(
                lentOutstanding.setScale(2, RoundingMode.HALF_UP),
                borrowedOutstanding.setScale(2, RoundingMode.HALF_UP),
                netReceivable.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public record LendingTotals(BigDecimal lentOutstanding, BigDecimal borrowedOutstanding, BigDecimal netReceivable) {}

    @Transactional(readOnly = true)
    public List<ObligationItemDto> getUpcomingLendingObligations(LocalDate startDate, LocalDate endDate) {
        List<Counterparty> counterparties = counterpartyRepository.findAll();
        List<ObligationItemDto> items = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Counterparty cp : counterparties) {
            List<Lending> entries = lendingRepository.findByCounterparty_Id(cp.getId());
            BigDecimal cpLent = BigDecimal.ZERO;
            BigDecimal cpBorrowed = BigDecimal.ZERO;

            for (Lending l : entries) {
                if (l.getDirection() == LendingDirection.lent) {
                    cpLent = cpLent.add(l.getAmount());
                } else if (l.getDirection() == LendingDirection.borrowed) {
                    cpBorrowed = cpBorrowed.add(l.getAmount());
                }
            }

            BigDecimal net = cpLent.subtract(cpBorrowed);
            if (net.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            LendingDirection targetDir = net.compareTo(BigDecimal.ZERO) > 0 ? LendingDirection.lent : LendingDirection.borrowed;
            LocalDate earliestReturnDate = null;

            for (Lending e : entries) {
                if (e.getDirection() == targetDir && e.getExpectedReturnDate() != null) {
                    LocalDate date = e.getExpectedReturnDate();
                    boolean isOverdue = date.isBefore(today);
                    boolean isUpcoming = !date.isBefore(today) && !date.isAfter(endDate);

                    if (isOverdue || isUpcoming) {
                        if (earliestReturnDate == null || date.isBefore(earliestReturnDate)) {
                            earliestReturnDate = date;
                        }
                    }
                }
            }

            if (earliestReturnDate != null) {
                boolean isOverdue = earliestReturnDate.isBefore(today);
                items.add(new ObligationItemDto(
                        "lending_due",
                        earliestReturnDate,
                        net.abs().setScale(2, RoundingMode.HALF_UP),
                        isOverdue ? "overdue" : "upcoming",
                        null,
                        null,
                        null,
                        null,
                        cp.getId(),
                        cp.getName(),
                        targetDir
                ));
            }
        }
        return items;
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

    @Transactional(readOnly = true)
    public List<Lending> getAllLendings() {
        return lendingRepository.findAll();
    }
}
