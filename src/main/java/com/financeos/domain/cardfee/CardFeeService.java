package com.financeos.domain.cardfee;

import com.financeos.api.cardfee.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class CardFeeService {

    private final CardFeeTermRepository termRepository;
    private final CardFeeChargeRepository chargeRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    public CardFeeService(CardFeeTermRepository termRepository,
                          CardFeeChargeRepository chargeRepository,
                          AccountRepository accountRepository,
                          UserRepository userRepository,
                          TransactionRepository transactionRepository) {
        this.termRepository = termRepository;
        this.chargeRepository = chargeRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
    }

    public CardFeeTermResponse createTerm(CardFeeTermRequest request) {
        Account account = validateCreditCardAccount(request.accountId());

        validateTermRequest(request, account, null);

        User user = userRepository.getReferenceById(UserContext.getCurrentUserId());

        CardFeeTerm term = new CardFeeTerm();
        term.setUser(user);
        term.setAccount(account);
        term.setKind(request.kind());
        term.setEffectiveFrom(request.effectiveFrom());

        if (request.kind() == CardFeeKind.LTF) {
            term.setAmount(null);
            term.setGstRate(null);
            term.setWaiverSpendThreshold(null);
            term.setWaiverBasis(null);
        } else {
            term.setAmount(request.amount());
            BigDecimal gstRate = request.gstRate() != null ? request.gstRate() : BigDecimal.valueOf(18);
            term.setGstRate(gstRate);

            if (request.kind() == CardFeeKind.ANNUAL_FEE) {
                term.setWaiverSpendThreshold(request.waiverSpendThreshold());
                term.setWaiverBasis(request.waiverSpendThreshold() != null ? request.waiverBasis() : null);
            } else {
                term.setWaiverSpendThreshold(null);
                term.setWaiverBasis(null);
            }
        }
        term.setNote(request.note());

        CardFeeTerm saved = termRepository.save(term);
        return toTermResponse(saved, account);
    }

    public CardFeeTermResponse updateTerm(UUID id, CardFeeTermRequest request) {
        CardFeeTerm term = termRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CardFeeTerm", id));
        validateOwnership(term.getUser().getId());

        if (!term.getAccount().getId().equals(request.accountId())) {
            throw new ValidationException("Changing account ID of a fee term is not allowed.");
        }

        Account account = term.getAccount();
        validateTermRequest(request, account, id);

        term.setKind(request.kind());
        term.setEffectiveFrom(request.effectiveFrom());

        if (request.kind() == CardFeeKind.LTF) {
            term.setAmount(null);
            term.setGstRate(null);
            term.setWaiverSpendThreshold(null);
            term.setWaiverBasis(null);
        } else {
            term.setAmount(request.amount());
            BigDecimal gstRate = request.gstRate() != null ? request.gstRate() : BigDecimal.valueOf(18);
            term.setGstRate(gstRate);

            if (request.kind() == CardFeeKind.ANNUAL_FEE) {
                term.setWaiverSpendThreshold(request.waiverSpendThreshold());
                term.setWaiverBasis(request.waiverSpendThreshold() != null ? request.waiverBasis() : null);
            } else {
                term.setWaiverSpendThreshold(null);
                term.setWaiverBasis(null);
            }
        }
        term.setNote(request.note());

        CardFeeTerm saved = termRepository.save(term);
        return toTermResponse(saved, account);
    }

    public void deleteTerm(UUID id) {
        CardFeeTerm term = termRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CardFeeTerm", id));
        validateOwnership(term.getUser().getId());
        termRepository.delete(term);
    }

    @Transactional(readOnly = true)
    public List<CardFeeTermResponse> getTermsByAccountId(UUID accountId) {
        Account account = validateCreditCardAccount(accountId);
        List<CardFeeTerm> terms = termRepository.findByAccountIdOrderByEffectiveFromAsc(accountId);
        return terms.stream().map(t -> toTermResponse(t, account)).toList();
    }

    public CardFeeChargeResponse upsertCharge(CardFeeChargeRequest request) {
        Account account = validateCreditCardAccount(request.accountId());

        LocalDate canonicalFeeYearStart = calculateFeeYearStart(account.getRewardAnniversaryDate(), request.feeYearStart());

        // Validate transaction links if provided
        Set<UUID> txnIds = request.transactionIds() != null ? new HashSet<>(request.transactionIds()) : new HashSet<>();
        for (UUID txnId : txnIds) {
            Transaction txn = transactionRepository.findById(txnId)
                    .orElseThrow(() -> new ValidationException("Transaction not found: " + txnId));
            if (!txn.getAccount().getId().equals(account.getId())) {
                throw new ValidationException("Transaction " + txnId + " does not belong to account " + account.getId());
            }
            Optional<CardFeeCharge> existingLink = chargeRepository.findByTransactionId(txnId);
            if (existingLink.isPresent()) {
                CardFeeCharge linkedCharge = existingLink.get();
                if (!linkedCharge.getAccount().getId().equals(account.getId()) ||
                    linkedCharge.getKind() != request.kind() ||
                    !linkedCharge.getFeeYearStart().equals(canonicalFeeYearStart)) {
                    throw new ValidationException("Transaction " + txnId + " is already linked to another fee charge.");
                }
            }
        }

        CardFeeCharge charge = chargeRepository.findByAccountIdAndKindAndFeeYearStart(
                account.getId(), request.kind(), canonicalFeeYearStart
        ).orElseGet(() -> {
            CardFeeCharge c = new CardFeeCharge();
            c.setUser(userRepository.getReferenceById(UserContext.getCurrentUserId()));
            c.setAccount(account);
            c.setKind(request.kind());
            c.setFeeYearStart(canonicalFeeYearStart);
            return c;
        });

        charge.setWaived(request.waived());
        charge.setOverrideAmount(request.overrideAmount());
        charge.setTransactionIds(txnIds);
        charge.setNote(request.note());

        CardFeeCharge saved = chargeRepository.save(charge);
        return toChargeResponse(saved);
    }

    public void deleteCharge(UUID accountId, CardFeeKind kind, LocalDate feeYearStart) {
        Account account = validateCreditCardAccount(accountId);
        LocalDate canonicalFeeYearStart = calculateFeeYearStart(account.getRewardAnniversaryDate(), feeYearStart);

        CardFeeCharge charge = chargeRepository.findByAccountIdAndKindAndFeeYearStart(
                account.getId(), kind, canonicalFeeYearStart
        ).orElseThrow(() -> new ResourceNotFoundException("CardFeeCharge", accountId));

        validateOwnership(charge.getUser().getId());
        chargeRepository.delete(charge);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CardFeeService.class);

    @Transactional(readOnly = true)
    public List<FeeChargeCandidateResponse> getCandidates(UUID accountId, CardFeeKind kind, LocalDate feeYearStart) {
        Account account = validateCreditCardAccount(accountId);
        LocalDate canonicalFeeYearStart = calculateFeeYearStart(account.getRewardAnniversaryDate(), feeYearStart);

        LocalDate dueDate = canonicalFeeYearStart;
        if (kind == CardFeeKind.JOINING_FEE) {
            List<CardFeeTerm> terms = termRepository.findByAccountIdOrderByEffectiveFromAsc(accountId);
            CardFeeTerm joiningTerm = terms.stream().filter(t -> t.getKind() == CardFeeKind.JOINING_FEE).findFirst().orElse(null);
            if (joiningTerm != null) {
                dueDate = joiningTerm.getEffectiveFrom();
            }
        }

        LocalDate minDate = dueDate.minusDays(20);
        LocalDate maxDate = dueDate.plusDays(20);

        List<UUID> allLinkedIds = chargeRepository.findAllLinkedTransactionIdsByAccountId(accountId);
        Optional<CardFeeCharge> currentCharge = chargeRepository.findByAccountIdAndKindAndFeeYearStart(accountId, kind, canonicalFeeYearStart);
        Set<UUID> currentTxnIds = currentCharge.map(c -> new HashSet<>(c.getTransactionIds())).orElseGet(HashSet::new);
        Set<UUID> linkedElsewhere = allLinkedIds.stream()
                .filter(id -> !currentTxnIds.contains(id))
                .collect(Collectors.toSet());

        List<Transaction> txns = transactionRepository.findByAccountIdAndDateRange(accountId, minDate, maxDate).stream()
                .filter(t -> !linkedElsewhere.contains(t.getId()))
                .toList();

        // Calculate expected target amount for sorting candidates
        BigDecimal targetTotal = BigDecimal.ZERO;
        List<CardFeeTerm> terms = termRepository.findByAccountIdOrderByEffectiveFromAsc(accountId);
        CardFeeTerm activeTerm = findTermInForce(terms, kind, dueDate);
        if (activeTerm != null && activeTerm.getKind() != CardFeeKind.LTF && activeTerm.getAmount() != null) {
            BigDecimal base = activeTerm.getAmount();
            BigDecimal gstRate = activeTerm.getGstRate() != null ? activeTerm.getGstRate() : BigDecimal.valueOf(18);
            BigDecimal gst = base.multiply(gstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            targetTotal = base.add(gst);
        }

        final BigDecimal target = targetTotal;
        return txns.stream()
                .sorted(Comparator.comparing((Transaction t) -> t.getAmount().subtract(target).abs())
                        .thenComparing(Transaction::getDate))
                .limit(20)
                .map(t -> new FeeChargeCandidateResponse(
                        t.getId(),
                        t.getDate(),
                        t.getDescription(),
                        t.getSourcedDescription(),
                        t.getAmount(),
                        t.getType(),
                        t.getAmount().subtract(target)
                ))
                .toList();
    }

    // ---------- Helper Methods ----------

    public Account validateCreditCardAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        validateOwnership(account.getUser().getId());
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Card fee operations are only supported for credit card accounts.");
        }
        return account;
    }

    private void validateOwnership(UUID ownerUserId) {
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId == null || !ownerUserId.equals(currentUserId)) {
            log.error("Security Breach Attempt: User {} tried to access card fee resource owned by User {}", currentUserId, ownerUserId);
            throw new ValidationException("You do not have permission to modify this resource.");
        }
    }

    private void validateTermRequest(CardFeeTermRequest request, Account account, UUID currentTermId) {
        if (account.getClosedOn() != null && request.effectiveFrom().isAfter(account.getClosedOn())) {
            throw new ValidationException("Effective from date cannot be after account close date.");
        }

        List<CardFeeTerm> existingTerms = termRepository.findByAccountIdOrderByEffectiveFromAsc(account.getId());

        if (request.kind() == CardFeeKind.LTF) {
            if (request.amount() != null || request.gstRate() != null || request.waiverSpendThreshold() != null || request.waiverBasis() != null) {
                throw new ValidationException("LTF fee terms must not specify amount, GST rate, or waiver settings.");
            }
        } else {
            if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException("Fee amount must be greater than zero for " + request.kind() + " terms.");
            }
            if (request.kind() == CardFeeKind.ANNUAL_FEE) {
                if (request.waiverSpendThreshold() != null) {
                    if (request.waiverSpendThreshold().compareTo(BigDecimal.ZERO) <= 0) {
                        throw new ValidationException("Waiver spend threshold must be greater than zero.");
                    }
                    if (request.waiverBasis() == null) {
                        throw new ValidationException("Waiver basis is required when waiver spend threshold is specified.");
                    }
                }
            } else if (request.kind() == CardFeeKind.JOINING_FEE) {
                if (request.waiverSpendThreshold() != null || request.waiverBasis() != null) {
                    throw new ValidationException("Joining fee terms cannot have waiver thresholds.");
                }
                // Check if another joining fee term exists
                boolean existingJoining = existingTerms.stream()
                        .anyMatch(t -> t.getKind() == CardFeeKind.JOINING_FEE && !t.getId().equals(currentTermId));
                if (existingJoining) {
                    throw new ValidationException("An account can have at most one joining fee term.");
                }
            }
        }

        if (request.kind() != CardFeeKind.JOINING_FEE) {
            Optional<CardFeeTerm> duplicateRecurring = existingTerms.stream()
                    .filter(t -> t.getKind() != CardFeeKind.JOINING_FEE)
                    .filter(t -> !t.getId().equals(currentTermId))
                    .filter(t -> t.getEffectiveFrom().equals(request.effectiveFrom()))
                    .findFirst();
            if (duplicateRecurring.isPresent()) {
                throw new ValidationException("A recurring fee term (" + duplicateRecurring.get().getKind() + ") already exists starting on " + request.effectiveFrom());
            }
        }
    }

    public static LocalDate calculateFeeYearStart(LocalDate anniversaryDate, LocalDate targetDate) {
        if (anniversaryDate == null) {
            return LocalDate.of(targetDate.getYear(), 1, 1);
        }
        int year = targetDate.getYear();
        LocalDate thisYearAnniversary = safeLocalDate(year, anniversaryDate.getMonthValue(), anniversaryDate.getDayOfMonth());
        if (targetDate.isBefore(thisYearAnniversary)) {
            return safeLocalDate(year - 1, anniversaryDate.getMonthValue(), anniversaryDate.getDayOfMonth());
        } else {
            return thisYearAnniversary;
        }
    }

    public static LocalDate calculateFirstGovernedFeeYearStart(LocalDate anniversaryDate, LocalDate effectiveFrom) {
        LocalDate start = calculateFeeYearStart(anniversaryDate, effectiveFrom);
        if (effectiveFrom.equals(start)) {
            return effectiveFrom;
        } else {
            return start.plusYears(1);
        }
    }

    private CardFeeTerm findTermInForce(List<CardFeeTerm> terms, CardFeeKind kind, LocalDate date) {
        return terms.stream()
                .filter(t -> t.getKind() == kind || (kind != CardFeeKind.JOINING_FEE && t.getKind() != CardFeeKind.JOINING_FEE))
                .filter(t -> !t.getEffectiveFrom().isAfter(date))
                .max(Comparator.comparing(CardFeeTerm::getEffectiveFrom))
                .orElse(null);
    }

    private static LocalDate safeLocalDate(int year, int month, int day) {
        int maxDay = java.time.YearMonth.of(year, month).lengthOfMonth();
        return LocalDate.of(year, month, Math.min(day, maxDay));
    }

    private CardFeeTermResponse toTermResponse(CardFeeTerm term, Account account) {
        BigDecimal base = term.getAmount();
        BigDecimal total = null;
        if (base != null) {
            BigDecimal gstRate = term.getGstRate() != null ? term.getGstRate() : BigDecimal.valueOf(18);
            BigDecimal gst = base.multiply(gstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            total = base.add(gst);
        }
        LocalDate firstGoverned = calculateFirstGovernedFeeYearStart(account.getRewardAnniversaryDate(), term.getEffectiveFrom());

        return new CardFeeTermResponse(
                term.getId(),
                account.getId(),
                term.getKind(),
                term.getEffectiveFrom(),
                term.getAmount(),
                term.getGstRate(),
                total,
                term.getWaiverSpendThreshold(),
                term.getWaiverBasis(),
                term.getNote(),
                firstGoverned
        );
    }

    private CardFeeChargeResponse toChargeResponse(CardFeeCharge charge) {
        return new CardFeeChargeResponse(
                charge.getId(),
                charge.getAccount().getId(),
                charge.getKind(),
                charge.getFeeYearStart(),
                charge.getWaived(),
                charge.getOverrideAmount(),
                charge.getTransactionIds(),
                charge.getNote()
        );
    }
}
