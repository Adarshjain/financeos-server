package com.financeos.domain.loan;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.api.loan.dto.*;
import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
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
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final LoanRepository loanRepository;
    private final LoanEventRepository loanEventRepository;
    private final LoanPaymentRepository loanPaymentRepository;
    private final LoanChargeRepository loanChargeRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final LoanScheduleService scheduleService;
    private final TransactionReferenceValidator transactionValidator;

    public LoanService(
            LoanRepository loanRepository,
            LoanEventRepository loanEventRepository,
            LoanPaymentRepository loanPaymentRepository,
            LoanChargeRepository loanChargeRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            UserRepository userRepository,
            LoanScheduleService scheduleService,
            TransactionReferenceValidator transactionValidator) {
        this.loanRepository = loanRepository;
        this.loanEventRepository = loanEventRepository;
        this.loanPaymentRepository = loanPaymentRepository;
        this.loanChargeRepository = loanChargeRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.scheduleService = scheduleService;
        this.transactionValidator = transactionValidator;
    }

    public LoanResponse createLoan(CreateLoanRequest req) {
        UUID userId = UserContext.getCurrentUserId();
        Account paymentAccount = null;
        if (req.paymentAccountId() != null) {
            paymentAccount = accountRepository.findById(req.paymentAccountId())
                    .orElseThrow(() -> new ValidationException("Payment account not found: " + req.paymentAccountId()));
            if (paymentAccount.getUser() == null || !paymentAccount.getUser().getId().equals(userId)) {
                log.warn("Security Breach Attempt: User {} tried to link foreign payment account {}", userId, req.paymentAccountId());
                throw new ValidationException("Payment account does not belong to user");
            }
            if (paymentAccount.getType() != AccountType.bank_account) {
                throw new ValidationException("Payment account must be a bank_account");
            }
        }

        BigDecimal emi = req.emiAmount();
        if (emi == null || emi.compareTo(BigDecimal.ZERO) <= 0) {
            emi = scheduleService.calculateAnnuityEmi(req.principal(), req.annualRatePct(), req.tenureMonths());
        }

        Loan loan = new Loan();
        loan.setUser(userRepository.getReferenceById(userId));
        loan.setName(req.name());
        loan.setLoanType(req.loanType());
        loan.setLender(req.lender());
        loan.setLoanAccountNumber(req.loanAccountNumber());
        loan.setPaymentAccount(paymentAccount);
        loan.setPrincipal(req.principal());
        loan.setAnnualRatePct(req.annualRatePct());
        loan.setRateType(req.rateType());
        loan.setTenureMonths(req.tenureMonths());
        loan.setStartDate(req.startDate());
        loan.setFirstEmiDate(req.firstEmiDate());
        loan.setEmiAmount(emi);
        loan.setStatus(LoanStatus.active);
        loan.setNotes(req.notes());

        // Validate schedule computation
        ScheduleResult schedule = scheduleService.compute(loan, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        Loan saved = loanRepository.save(loan);
        return LoanResponse.from(saved, schedule);
    }

    @Transactional(readOnly = true)
    public Page<LoanResponse> getLoans(LoanStatus statusFilter, Pageable pageable) {
        Page<Loan> loans = (statusFilter != null)
                ? loanRepository.findByStatus(statusFilter, pageable)
                : loanRepository.findAll(pageable);

        return loans.map(loan -> {
            List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loan.getId());
            List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loan.getId());
            List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loan.getId());
            ScheduleResult schedule = scheduleService.compute(loan, events, payments, charges);
            return LoanResponse.from(loan, schedule);
        });
    }

    @Transactional(readOnly = true)
    public LoanDetailResponse getLoanDetail(UUID loanId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);

        ScheduleResult schedule = scheduleService.compute(loan, events, payments, charges);

        List<LoanEventResponse> eventResponses = events.stream().map(LoanEventResponse::from).toList();
        List<LoanChargeResponse> chargeResponses = charges.stream().map(LoanChargeResponse::from).toList();

        return new LoanDetailResponse(LoanResponse.from(loan, schedule), eventResponses, chargeResponses);
    }

    @Transactional(readOnly = true)
    public List<InstallmentDto> getLoanSchedule(UUID loanId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);

        ScheduleResult schedule = scheduleService.compute(loan, events, payments, charges);
        return schedule.installments();
    }

    public LoanResponse updateLoan(UUID loanId, UpdateLoanRequest req) {
        Loan loan = getLoanAndVerifyOwnership(loanId);

        boolean coreTermsChanged = (req.principal() != null && req.principal().compareTo(loan.getPrincipal()) != 0)
                || (req.annualRatePct() != null && req.annualRatePct().compareTo(loan.getAnnualRatePct()) != 0)
                || (req.rateType() != null && req.rateType() != loan.getRateType())
                || (req.tenureMonths() != null && !req.tenureMonths().equals(loan.getTenureMonths()))
                || (req.startDate() != null && !req.startDate().equals(loan.getStartDate()))
                || (req.firstEmiDate() != null && !req.firstEmiDate().equals(loan.getFirstEmiDate()))
                || (req.emiAmount() != null && req.emiAmount().compareTo(loan.getEmiAmount()) != 0);

        if (coreTermsChanged) {
            long eventCount = loanRepository.countEventsByLoanId(loanId);
            long paymentCount = loanRepository.countPaymentsByLoanId(loanId);
            if (eventCount > 0 || paymentCount > 0) {
                throw new ValidationException("Core loan terms (principal, rate, tenure, dates, EMI) cannot be edited after events or payments exist");
            }
        }

        if (req.name() != null && !req.name().isBlank()) loan.setName(req.name());
        if (req.lender() != null && !req.lender().isBlank()) loan.setLender(req.lender());
        if (req.loanAccountNumber() != null) loan.setLoanAccountNumber(req.loanAccountNumber());
        if (req.notes() != null) loan.setNotes(req.notes());

        if (req.paymentAccountId() != null) {
            UUID userId = UserContext.getCurrentUserId();
            Account paymentAccount = accountRepository.findById(req.paymentAccountId())
                    .orElseThrow(() -> new ValidationException("Payment account not found: " + req.paymentAccountId()));
            if (paymentAccount.getUser() == null || !paymentAccount.getUser().getId().equals(userId)) {
                log.warn("Security Breach Attempt: User {} tried to link foreign payment account {}", userId, req.paymentAccountId());
                throw new ValidationException("Payment account does not belong to user");
            }
            if (paymentAccount.getType() != AccountType.bank_account) {
                throw new ValidationException("Payment account must be a bank_account");
            }
            loan.setPaymentAccount(paymentAccount);
        }

        if (coreTermsChanged) {
            if (req.principal() != null) loan.setPrincipal(req.principal());
            if (req.annualRatePct() != null) loan.setAnnualRatePct(req.annualRatePct());
            if (req.rateType() != null) loan.setRateType(req.rateType());
            if (req.tenureMonths() != null) loan.setTenureMonths(req.tenureMonths());
            if (req.startDate() != null) loan.setStartDate(req.startDate());
            if (req.firstEmiDate() != null) loan.setFirstEmiDate(req.firstEmiDate());
            if (req.emiAmount() != null) loan.setEmiAmount(req.emiAmount());
        }

        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);

        ScheduleResult schedule = scheduleService.compute(loan, events, payments, charges);

        Loan saved = loanRepository.save(loan);
        return LoanResponse.from(saved, schedule);
    }

    public void deleteLoan(UUID loanId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        loanRepository.delete(loan);
    }

    public void closeLoan(UUID loanId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        loan.setStatus(LoanStatus.closed);
        loanRepository.save(loan);
    }

    public void reopenLoan(UUID loanId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        boolean hasForeclosure = events.stream().anyMatch(e -> e.getEventType() == LoanEventType.foreclosure);
        if (hasForeclosure) {
            throw new ValidationException("Cannot reopen loan while a foreclosure event exists. Delete the foreclosure event first.");
        }
        loan.setStatus(LoanStatus.active);
        loanRepository.save(loan);
    }

    public LoanEventResponse addEvent(UUID loanId, CreateLoanEventRequest req) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        if (loan.getStatus() == LoanStatus.closed) {
            throw new ValidationException("Cannot add event to closed loan");
        }
        if (req.effectiveDate().isBefore(loan.getStartDate())) {
            throw new ValidationException("Event effective date (" + req.effectiveDate() + ") cannot be before loan start date (" + loan.getStartDate() + ")");
        }

        List<LoanEvent> existingEvents = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        Optional<LoanEvent> existingForeclosure = existingEvents.stream()
                .filter(e -> e.getEventType() == LoanEventType.foreclosure)
                .findFirst();
        if (existingForeclosure.isPresent() && req.effectiveDate().isAfter(existingForeclosure.get().getEffectiveDate())) {
            throw new ValidationException("Cannot add event dated after existing foreclosure date (" + existingForeclosure.get().getEffectiveDate() + ")");
        }

        AdjustmentMode mode = req.adjustmentMode();
        if (req.eventType() == LoanEventType.rate_change) {
            if (req.newAnnualRatePct() == null || req.newAnnualRatePct().compareTo(BigDecimal.ZERO) <= 0 || req.newAnnualRatePct().compareTo(new BigDecimal("60")) > 0) {
                throw new ValidationException("newAnnualRatePct between 0 and 60 is required for rate_change event");
            }
            if (mode == null) mode = AdjustmentMode.reduce_tenure;
        } else if (req.eventType() == LoanEventType.prepayment) {
            if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException("Amount > 0 is required for prepayment event");
            }

            List<LoanPayment> existingPayments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
            List<LoanCharge> existingCharges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);
            ScheduleResult existingSchedule = scheduleService.compute(loan, existingEvents, existingPayments, existingCharges);

            BigDecimal baseOutstanding;
            LocalDate lastDueDate = null;
            InstallmentDto lastPastInstallment = null;
            for (InstallmentDto inst : existingSchedule.installments()) {
                if (!inst.dueDate().isAfter(req.effectiveDate())) {
                    lastPastInstallment = inst;
                }
            }
            if (lastPastInstallment != null) {
                baseOutstanding = lastPastInstallment.closingBalance();
                lastDueDate = lastPastInstallment.dueDate();
            } else {
                baseOutstanding = loan.getPrincipal();
            }

            BigDecimal sameOrInterimPrepayments = BigDecimal.ZERO;
            for (LoanEvent e : existingEvents) {
                if (e.getEventType() == LoanEventType.prepayment) {
                    boolean afterLastDueDate = (lastDueDate == null) || e.getEffectiveDate().isAfter(lastDueDate);
                    boolean onOrBeforeEffectiveDate = !e.getEffectiveDate().isAfter(req.effectiveDate());
                    if (afterLastDueDate && onOrBeforeEffectiveDate) {
                        sameOrInterimPrepayments = sameOrInterimPrepayments.add(e.getAmount());
                    }
                }
            }

            BigDecimal modeledOutstanding = baseOutstanding.subtract(sameOrInterimPrepayments).max(BigDecimal.ZERO);
            if (req.amount().compareTo(modeledOutstanding) >= 0) {
                throw new ValidationException("Prepayment amount (" + req.amount() + ") must be less than modeled outstanding principal as of " + req.effectiveDate() + " (" + modeledOutstanding + "). Record a foreclosure event for full payoff.");
            }

            if (mode == null) mode = AdjustmentMode.reduce_tenure;
        } else if (req.eventType() == LoanEventType.foreclosure) {
            if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException("Amount > 0 is required for foreclosure event");
            }
            if (existingForeclosure.isPresent()) {
                throw new ValidationException("At most one foreclosure event is allowed per loan");
            }
            if (existingEvents.stream().anyMatch(e -> e.getEffectiveDate().isAfter(req.effectiveDate()))) {
                throw new ValidationException("No event may be dated after a foreclosure date");
            }
            mode = null;
        }

        if (req.newEmiOverride() != null && mode != AdjustmentMode.reduce_emi) {
            throw new ValidationException("newEmiOverride is only valid when adjustmentMode is reduce_emi");
        }

        Transaction transaction = transactionValidator.validateAndGetTransaction(req.transactionId());

        LoanEvent event = new LoanEvent();
        event.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
        event.setLoan(loan);
        event.setEventType(req.eventType());
        event.setEffectiveDate(req.effectiveDate());
        event.setNewAnnualRatePct(req.newAnnualRatePct());
        event.setAmount(req.amount());
        event.setAdjustmentMode(mode);
        event.setNewEmiOverride(req.newEmiOverride());
        event.setTransaction(transaction);

        LoanEvent saved = loanEventRepository.save(event);

        if (req.eventType() == LoanEventType.foreclosure) {
            loan.setStatus(LoanStatus.foreclosed);
            loanRepository.save(loan);
        }

        // Re-validate schedule computation
        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);
        scheduleService.compute(loan, events, payments, charges);

        return LoanEventResponse.from(saved);
    }

    public void deleteEvent(UUID loanId, UUID eventId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        LoanEvent event = loanEventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanEvent", eventId));

        if (!event.getLoan().getId().equals(loanId)) {
            throw new ValidationException("Event does not belong to loan " + loanId);
        }

        if (event.getEventType() == LoanEventType.foreclosure) {
            loan.setStatus(LoanStatus.active);
            loanRepository.save(loan);
        }

        loanEventRepository.delete(event);
        loanEventRepository.flush();

        // Re-validate schedule and verify settled payments exist in new schedule
        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);

        ScheduleResult newSchedule = scheduleService.compute(loan, events, payments, charges);
        int newLength = newSchedule.installments().size();

        for (LoanPayment p : payments) {
            if (p.getInstallmentSeq() > newLength) {
                throw new ValidationException("Cannot delete event: settled payment exists at seq " + p.getInstallmentSeq() + " beyond new schedule length (" + newLength + ")");
            }
        }
    }

    public LoanPaymentResponse addPayment(UUID loanId, CreateLoanPaymentRequest req) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Payment amount must be greater than zero");
        }

        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);
        ScheduleResult schedule = scheduleService.compute(loan, events, payments, charges);

        Integer targetSeq = req.installmentSeq();
        if (targetSeq == null) {
            // Auto-assign lowest unsettled seq
            targetSeq = schedule.installments().stream()
                    .filter(i -> !"settled".equals(i.status()))
                    .map(InstallmentDto::seq)
                    .findFirst()
                    .orElseThrow(() -> new ValidationException("All installments in the loan schedule are already settled"));
        } else {
            Integer seqToFind = targetSeq;
            InstallmentDto installment = schedule.installments().stream()
                    .filter(i -> i.seq().equals(seqToFind))
                    .findFirst()
                    .orElseThrow(() -> new ValidationException("Installment seq " + seqToFind + " does not exist in loan schedule"));

            if ("settled".equals(installment.status())) {
                throw new ValidationException("Installment seq " + targetSeq + " is already settled");
            }
        }

        Transaction transaction = null;
        if (req.transactionId() != null) {
            transaction = transactionValidator.validateAndGetTransaction(req.transactionId());
            if (transaction.getType() != TransactionType.DEBIT) {
                throw new ValidationException("Transaction " + req.transactionId() + " must be a DEBIT transaction");
            }
        }

        LoanPayment payment = new LoanPayment();
        payment.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
        payment.setLoan(loan);
        payment.setInstallmentSeq(targetSeq);
        payment.setPaymentDate(req.paymentDate());
        payment.setAmount(req.amount());
        payment.setTransaction(transaction);

        LoanPayment saved = loanPaymentRepository.save(payment);
        return LoanPaymentResponse.from(saved);
    }

    public Map<String, Integer> addPaymentsBatch(UUID loanId, BatchLoanPaymentRequest batchReq) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        if (batchReq.items() == null || batchReq.items().isEmpty() || batchReq.items().size() > 500) {
            throw new ValidationException("Batch must contain between 1 and 500 items");
        }

        Set<Integer> seqsInBatch = new HashSet<>();
        Set<UUID> txIdsInBatch = new HashSet<>();
        List<String> offendingDetails = new ArrayList<>();

        for (int i = 0; i < batchReq.items().size(); i++) {
            BatchLoanPaymentItem item = batchReq.items().get(i);
            if (item.installmentSeq() != null) {
                if (!seqsInBatch.add(item.installmentSeq())) {
                    offendingDetails.add("Item " + i + ": Duplicate installmentSeq " + item.installmentSeq() + " within batch");
                }
            }
            if (item.transactionId() != null) {
                if (!txIdsInBatch.add(item.transactionId())) {
                    offendingDetails.add("Item " + i + ": Duplicate transactionId " + item.transactionId() + " within batch");
                }
            }
        }

        if (!offendingDetails.isEmpty()) {
            throw new ValidationException("Batch validation failed: " + String.join("; ", offendingDetails));
        }

        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> existingPayments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);
        ScheduleResult schedule = scheduleService.compute(loan, events, existingPayments, charges);

        Set<Integer> availableSeqs = new HashSet<>();
        for (InstallmentDto inst : schedule.installments()) {
            if (!"settled".equals(inst.status())) {
                availableSeqs.add(inst.seq());
            }
        }

        List<LoanPayment> newPayments = new ArrayList<>();
        UUID userId = UserContext.getCurrentUserId();

        for (int i = 0; i < batchReq.items().size(); i++) {
            final int itemIndex = i;
            BatchLoanPaymentItem item = batchReq.items().get(i);
            Integer seq = item.installmentSeq();
            if (seq == null) {
                seq = availableSeqs.stream().sorted().findFirst()
                        .orElseThrow(() -> new ValidationException("Batch item " + itemIndex + ": No unsettled installment available"));
            }
            if (!availableSeqs.contains(seq)) {
                offendingDetails.add("Item " + i + ": Installment seq " + seq + " is invalid or already settled");
            }
            availableSeqs.remove(seq);

            Transaction transaction = null;
            if (item.transactionId() != null) {
                try {
                    transaction = transactionValidator.validateAndGetTransaction(item.transactionId());
                    if (transaction.getType() != TransactionType.DEBIT) {
                        offendingDetails.add("Item " + i + ": Transaction " + item.transactionId() + " is not a DEBIT transaction");
                    }
                } catch (Exception e) {
                    offendingDetails.add("Item " + i + ": " + e.getMessage());
                }
            }

            LoanPayment p = new LoanPayment();
            p.setUser(userRepository.getReferenceById(userId));
            p.setLoan(loan);
            p.setInstallmentSeq(seq);
            p.setPaymentDate(item.paymentDate());
            p.setAmount(item.amount());
            p.setTransaction(transaction);
            newPayments.add(p);
        }

        if (!offendingDetails.isEmpty()) {
            throw new ValidationException("Batch rejected due to errors: " + String.join("; ", offendingDetails));
        }

        loanPaymentRepository.saveAll(newPayments);
        return Map.of("created", newPayments.size());
    }

    public void deletePayment(UUID loanId, UUID paymentId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        LoanPayment payment = loanPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanPayment", paymentId));

        if (!payment.getLoan().getId().equals(loanId)) {
            throw new ValidationException("Payment does not belong to loan " + loanId);
        }

        loanPaymentRepository.delete(payment);
    }

    public LoanChargeResponse addCharge(UUID loanId, CreateLoanChargeRequest req) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Charge amount must be greater than zero");
        }

        Transaction transaction = transactionValidator.validateAndGetTransaction(req.transactionId());

        LoanCharge charge = new LoanCharge();
        charge.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
        charge.setLoan(loan);
        charge.setChargeType(req.chargeType());
        charge.setAmount(req.amount());
        charge.setChargeDate(req.chargeDate());
        charge.setTransaction(transaction);
        charge.setNotes(req.notes());

        LoanCharge saved = loanChargeRepository.save(charge);
        return LoanChargeResponse.from(saved);
    }

    public void deleteCharge(UUID loanId, UUID chargeId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        LoanCharge charge = loanChargeRepository.findById(chargeId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanCharge", chargeId));

        if (!charge.getLoan().getId().equals(loanId)) {
            throw new ValidationException("Charge does not belong to loan " + loanId);
        }

        loanChargeRepository.delete(charge);
    }

    @Transactional(readOnly = true)
    public MatchSuggestionsResponse getMatchSuggestions(UUID loanId) {
        Loan loan = getLoanAndVerifyOwnership(loanId);
        List<LoanEvent> events = loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loanId);
        List<LoanPayment> payments = loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loanId);
        List<LoanCharge> charges = loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loanId);
        ScheduleResult schedule = scheduleService.compute(loan, events, payments, charges);

        LocalDate today = LocalDate.now();
        LocalDate maxDueDate = today.plusDays(7);

        List<InstallmentDto> targetInstallments = schedule.installments().stream()
                .filter(i -> !"settled".equals(i.status()))
                .filter(i -> !i.dueDate().isAfter(maxDueDate))
                .sorted(Comparator.comparing(InstallmentDto::seq))
                .toList();

        if (targetInstallments.isEmpty()) {
            return new MatchSuggestionsResponse(Collections.emptyList());
        }

        Set<UUID> referencedTxIds = transactionValidator.getAllReferencedTransactionIds();

        UUID paymentAccountId = loan.getPaymentAccount() != null ? loan.getPaymentAccount().getId() : null;

        // Fetch candidate transactions for all target installments
        Map<Integer, List<Transaction>> candidateMap = new HashMap<>();

        for (InstallmentDto inst : targetInstallments) {
            BigDecimal expectedAmount = inst.emi().setScale(2, RoundingMode.HALF_UP);
            LocalDate minDate = inst.dueDate().minusDays(7);
            LocalDate maxDate = inst.dueDate().plusDays(7);

            List<Transaction> candidates;
            if (paymentAccountId != null) {
                candidates = transactionRepository.findMatchCandidatesByAccount(
                        TransactionType.DEBIT,
                        expectedAmount,
                        minDate,
                        maxDate,
                        paymentAccountId
                );
            } else {
                candidates = transactionRepository.findMatchCandidates(
                        TransactionType.DEBIT,
                        expectedAmount,
                        minDate,
                        maxDate
                );
            }

            // Filter out referenced transaction IDs
            List<Transaction> unreferenced = candidates.stream()
                    .filter(t -> !referencedTxIds.contains(t.getId()))
                    .toList();

            candidateMap.put(inst.seq(), new ArrayList<>(unreferenced));
        }

        // Greedy assignment: each candidate assigned to at most ONE installment whose dueDate is nearest (ties -> lower seq)
        Map<UUID, Integer> txToSeqAssignment = new HashMap<>();
        Map<UUID, Transaction> candidateTxObjects = new HashMap<>();

        for (InstallmentDto inst : targetInstallments) {
            List<Transaction> candidates = candidateMap.getOrDefault(inst.seq(), Collections.emptyList());
            for (Transaction tx : candidates) {
                candidateTxObjects.put(tx.getId(), tx);
                if (!txToSeqAssignment.containsKey(tx.getId())) {
                    txToSeqAssignment.put(tx.getId(), inst.seq());
                } else {
                    Integer existingSeq = txToSeqAssignment.get(tx.getId());
                    InstallmentDto existingInst = targetInstallments.stream().filter(i -> i.seq().equals(existingSeq)).findFirst().orElse(null);
                    if (existingInst != null) {
                        long diffNew = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(tx.getDate(), inst.dueDate()));
                        long diffOld = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(tx.getDate(), existingInst.dueDate()));
                        if (diffNew < diffOld || (diffNew == diffOld && inst.seq() < existingSeq)) {
                            txToSeqAssignment.put(tx.getId(), inst.seq());
                        }
                    }
                }
            }
        }

        List<MatchSuggestionsResponse.InstallmentMatchSuggestion> suggestions = new ArrayList<>();

        for (InstallmentDto inst : targetInstallments) {
            List<TransactionResponse> assignedCandidates = candidateTxObjects.values().stream()
                    .filter(tx -> Integer.valueOf(inst.seq()).equals(txToSeqAssignment.get(tx.getId())))
                    .map(TransactionResponse::from)
                    .toList();

            suggestions.add(new MatchSuggestionsResponse.InstallmentMatchSuggestion(
                    inst.seq(),
                    inst.dueDate(),
                    inst.emi(),
                    assignedCandidates
            ));
        }

        return new MatchSuggestionsResponse(suggestions);
    }

    private Loan getLoanAndVerifyOwnership(UUID loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (loan.getUser() == null || !loan.getUser().getId().equals(currentUserId)) {
            log.warn("Security Breach Attempt: User {} tried to access foreign loan {}", currentUserId, loanId);
            throw new ValidationException("You do not have permission to access loan " + loanId);
        }
        return loan;
    }
}
