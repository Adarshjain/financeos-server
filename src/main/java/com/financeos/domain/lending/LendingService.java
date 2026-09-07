package com.financeos.domain.lending;

import com.financeos.api.lending.dto.*;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import com.financeos.core.security.UserContext;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.obligation.MatchingConstants;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.UserRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public class LendingService {

    private static final Logger log = LoggerFactory.getLogger(LendingService.class);

    private final CounterpartyRepository counterpartyRepository;
    private final LendingRepository lendingRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceValidator transactionValidator;
    private final TransactionRepository transactionRepository;
    private final TransactionLinkRepository transactionLinkRepository;

    public LendingService(
            CounterpartyRepository counterpartyRepository,
            LendingRepository lendingRepository,
            UserRepository userRepository,
            TransactionReferenceValidator transactionValidator,
            TransactionRepository transactionRepository,
            TransactionLinkRepository transactionLinkRepository) {
        this.counterpartyRepository = counterpartyRepository;
        this.lendingRepository = lendingRepository;
        this.userRepository = userRepository;
        this.transactionValidator = transactionValidator;
        this.transactionRepository = transactionRepository;
        this.transactionLinkRepository = transactionLinkRepository;
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

        Transaction transaction = transactionValidator.validateForLending(req.transactionId(), req.direction());

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
        if (transaction != null) {
            logLinkEvent(Events.LENDING_LINKED, saved, transaction.getId());
        }
        return LendingResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<LendingResponse> getLendings(UUID counterpartyId, Pageable pageable) {
        Page<Lending> lendings;
        if (counterpartyId != null) {
            lendings = lendingRepository.findByCounterpartyIdWithRefs(counterpartyId, pageable);
        } else {
            lendings = lendingRepository.findAllWithRefs(pageable);
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

        if (req.direction() != null && req.direction() != lending.getDirection() && lending.getTransaction() != null) {
            throw new ValidationException("Unlink the transaction before changing the direction of this entry");
        }

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

    // --- Transaction link (PUT/DELETE /lendings/{id}/transaction) ---

    /** Attach or replace the linked bank transaction. Re-sending the current id is a no-op. */
    public LendingResponse linkTransaction(UUID lendingId, UUID transactionId) {
        if (transactionId == null) {
            throw new ValidationException("transactionId is required");
        }
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        if (lending.getTransaction() != null && transactionId.equals(lending.getTransaction().getId())) {
            return LendingResponse.from(lending);
        }
        Transaction transaction = transactionValidator.validateForLending(transactionId, lending.getDirection());
        lending.setTransaction(transaction);
        Lending saved = lendingRepository.save(lending);
        logLinkEvent(Events.LENDING_LINKED, saved, transactionId);
        return LendingResponse.from(saved);
    }

    /** Detach the linked transaction. Idempotent: an already-unlinked entry is left as is. */
    public void unlinkTransaction(UUID lendingId) {
        Lending lending = getLendingAndVerifyOwnership(lendingId);
        if (lending.getTransaction() == null) {
            return;
        }
        UUID previous = lending.getTransaction().getId();
        lending.setTransaction(null);
        lendingRepository.save(lending);
        logLinkEvent(Events.LENDING_UNLINKED, lending, previous);
    }

    // --- Match suggestions (mirrors LoanService#getMatchSuggestions) ---

    @Transactional(readOnly = true)
    public LendingMatchSuggestionsResponse getMatchSuggestions(UUID counterpartyId) {
        getCounterpartyAndVerifyOwnership(counterpartyId);

        List<Lending> entries = lendingRepository.findByCounterpartyIdWithRefs(counterpartyId).stream()
                .filter(l -> l.getTransaction() == null)
                .sorted(Comparator.comparing(Lending::getEntryDate).thenComparing(Lending::getCreatedAt))
                .toList();

        if (entries.isEmpty()) {
            logMatchAttempt(counterpartyId, null, null, 0, false, "no-unlinked-entries");
            return new LendingMatchSuggestionsResponse(Collections.emptyList());
        }

        Set<UUID> referencedTxIds = transactionValidator.getAllReferencedTransactionIds();

        // Fetch candidates per entry and drop those already referenced by a loan/lending row.
        Map<UUID, List<Transaction>> unreferencedByEntry = new LinkedHashMap<>();
        Map<UUID, Transaction> candidateTxObjects = new HashMap<>();

        for (Lending entry : entries) {
            TransactionType type = TransactionReferenceValidator.expectedTypeFor(entry.getDirection());
            BigDecimal minAmount = entry.getAmount().subtract(MatchingConstants.MATCH_AMOUNT_TOLERANCE);
            BigDecimal maxAmount = entry.getAmount().add(MatchingConstants.MATCH_AMOUNT_TOLERANCE);
            LocalDate minDate = entry.getEntryDate().minusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS);
            LocalDate maxDate = entry.getEntryDate().plusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS);

            List<Transaction> candidates = transactionRepository.findMatchCandidates(type, minAmount, maxAmount, minDate, maxDate);

            if (candidates.isEmpty()) {
                logMatchAttempt(counterpartyId, entry.getId(), null, 0, false, "no-candidates");
                unreferencedByEntry.put(entry.getId(), Collections.emptyList());
                continue;
            }

            List<Transaction> unreferenced = new ArrayList<>();
            for (Transaction t : candidates) {
                if (referencedTxIds.contains(t.getId())) {
                    logMatchAttempt(counterpartyId, entry.getId(), t.getId(), candidates.size(), false, "already-referenced");
                } else {
                    unreferenced.add(t);
                    candidateTxObjects.put(t.getId(), t);
                }
            }
            unreferencedByEntry.put(entry.getId(), unreferenced);
        }

        // Drop candidates that already sit in a transaction_links group (one query across all entries).
        Set<UUID> allCandidateIds = candidateTxObjects.keySet();
        Set<UUID> linkedTxIds = allCandidateIds.isEmpty()
                ? Collections.emptySet()
                : transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(allCandidateIds).stream()
                        .flatMap(link -> link.getMembers().stream())
                        .map(member -> member.getTransaction().getId())
                        .filter(allCandidateIds::contains)
                        .collect(Collectors.toSet());

        Map<UUID, List<Transaction>> candidateMap = new LinkedHashMap<>();
        for (Lending entry : entries) {
            List<Transaction> unreferenced = unreferencedByEntry.getOrDefault(entry.getId(), Collections.emptyList());
            List<Transaction> filtered = new ArrayList<>();
            for (Transaction t : unreferenced) {
                if (linkedTxIds.contains(t.getId())) {
                    logMatchAttempt(counterpartyId, entry.getId(), t.getId(), unreferenced.size(), false, "already-linked");
                } else {
                    filtered.add(t);
                }
            }
            candidateMap.put(entry.getId(), filtered);
        }

        // Greedy assignment: each candidate transaction goes to at most ONE entry -- the one whose
        // entryDate is nearest to the transaction date; ties -> the earlier entry in the sorted list.
        Map<UUID, Lending> entryById = new HashMap<>();
        Map<UUID, Integer> orderIndex = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            entryById.put(entries.get(i).getId(), entries.get(i));
            orderIndex.put(entries.get(i).getId(), i);
        }

        Map<UUID, UUID> txToEntryAssignment = new HashMap<>();
        for (Lending entry : entries) {
            for (Transaction tx : candidateMap.getOrDefault(entry.getId(), Collections.emptyList())) {
                UUID existingEntryId = txToEntryAssignment.get(tx.getId());
                if (existingEntryId == null) {
                    txToEntryAssignment.put(tx.getId(), entry.getId());
                    continue;
                }
                Lending existingEntry = entryById.get(existingEntryId);
                long diffNew = Math.abs(ChronoUnit.DAYS.between(tx.getDate(), entry.getEntryDate()));
                long diffOld = Math.abs(ChronoUnit.DAYS.between(tx.getDate(), existingEntry.getEntryDate()));
                if (diffNew < diffOld || (diffNew == diffOld && orderIndex.get(entry.getId()) < orderIndex.get(existingEntryId))) {
                    txToEntryAssignment.put(tx.getId(), entry.getId());
                }
            }
        }

        // Log final matched / assigned-elsewhere decisions and build the response.
        List<LendingMatchSuggestionsResponse.LendingMatchSuggestion> suggestions = new ArrayList<>();
        for (Lending entry : entries) {
            List<Transaction> filtered = candidateMap.getOrDefault(entry.getId(), Collections.emptyList());
            List<Transaction> assigned = new ArrayList<>();
            for (Transaction tx : filtered) {
                boolean matched = entry.getId().equals(txToEntryAssignment.get(tx.getId()));
                logMatchAttempt(counterpartyId, entry.getId(), tx.getId(), filtered.size(), matched, matched ? null : "assigned-elsewhere");
                if (matched) {
                    assigned.add(tx);
                }
            }
            if (assigned.isEmpty()) {
                continue;
            }

            assigned.sort(Comparator
                    .comparing((Transaction t) -> t.getAmount().subtract(entry.getAmount()).abs())
                    .thenComparing(t -> Math.abs(ChronoUnit.DAYS.between(entry.getEntryDate(), t.getDate()))));

            suggestions.add(new LendingMatchSuggestionsResponse.LendingMatchSuggestion(
                    entry.getId(),
                    entry.getDirection(),
                    entry.getAmount(),
                    entry.getEntryDate(),
                    entry.getExpectedReturnDate(),
                    entry.getNotes(),
                    assigned.stream().map(TransactionResponse::from).toList()
            ));
        }

        return new LendingMatchSuggestionsResponse(suggestions);
    }

    private void logMatchAttempt(UUID counterpartyId, UUID lendingId, UUID txnId, int candidateCount, boolean matched, String rejectReason) {
        log.info("Lending match attempted: counterpartyId={}, lendingId={}, txnId={}, matched={}, reason={}",
                counterpartyId, lendingId, txnId, matched, rejectReason,
                StructuredArguments.keyValue("event", Events.LENDING_MATCH_ATTEMPTED),
                StructuredArguments.keyValue("counterpartyId", counterpartyId != null ? counterpartyId.toString() : ""),
                StructuredArguments.keyValue("lendingId", lendingId != null ? lendingId.toString() : ""),
                StructuredArguments.keyValue("txnId", txnId != null ? txnId.toString() : ""),
                StructuredArguments.keyValue("toleranceUsed", MatchingConstants.MATCH_AMOUNT_TOLERANCE.doubleValue()),
                StructuredArguments.keyValue("dateWindowDays", MatchingConstants.MATCH_DATE_WINDOW_DAYS),
                StructuredArguments.keyValue("candidateCount", candidateCount),
                StructuredArguments.keyValue("matched", matched),
                StructuredArguments.keyValue("rejectReason", rejectReason != null ? rejectReason : ""));
    }

    private void logLinkEvent(String event, Lending lending, UUID transactionId) {
        log.info("Lending {}: lendingId={}, txnId={}", event, lending.getId(), transactionId,
                StructuredArguments.keyValue("event", event),
                StructuredArguments.keyValue("lendingId", String.valueOf(lending.getId())),
                StructuredArguments.keyValue("counterpartyId", String.valueOf(lending.getCounterparty().getId())),
                StructuredArguments.keyValue("direction", String.valueOf(lending.getDirection())),
                StructuredArguments.keyValue("txnId", String.valueOf(transactionId)));
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
        // findById (EntityManager.find) bypasses the Hibernate userFilter on purpose: a foreign row must
        // reach the ownership check below and answer 400 (the established "Security Breach" contract),
        // not vanish into a 404. The linked transaction/account load lazily inside the transaction.
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
        return lendingRepository.findAllWithRefs();
    }
}
