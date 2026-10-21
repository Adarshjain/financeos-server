package com.financeos.domain.lending;

import com.financeos.api.lending.dto.*;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.core.exception.DuplicateResourceException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.notification.lending.LendingReturnNotificationService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
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

    /** Sort key on {@code GET /counterparties} that orders by absolute net position, largest first. */
    public static final String SORT_BY_NET = "net";

    /**
     * Lists the caller's counterparties, paged. A non-blank {@code q} narrows to names containing it
     * (case-insensitive); {@code outstanding} keeps only people with a nonzero net position; a
     * {@code sort=net} (either direction) orders by absolute net position, largest first, then name.
     * Without either option this is the plain database page.
     */
    @Transactional(readOnly = true)
    public Page<CounterpartyResponse> getCounterparties(@Nullable String q, boolean outstanding, Pageable pageable) {
        return getCounterparties(q, outstanding, null, pageable);
    }

    /** As above, plus an optional {@code direction} filter on the sign of the net position. */
    @Transactional(readOnly = true)
    public Page<CounterpartyResponse> getCounterparties(@Nullable String q, boolean outstanding,
                                                        @Nullable CounterpartyDirection direction, Pageable pageable) {
        boolean byNet = pageable.getSort().getOrderFor(SORT_BY_NET) != null;
        if (!outstanding && !byNet && direction == null) {
            return getCounterparties(q, pageable);
        }
        String term = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        List<Object[]> rows = counterpartyRepository.findNetPositions(UserContext.getCurrentUserId());
        List<NetRow> nets = new ArrayList<>();
        for (Object[] row : rows) {
            UUID id = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
            String name = row[1] == null ? "" : row[1].toString();
            BigDecimal net = row[2] instanceof BigDecimal bd ? bd : new BigDecimal(row[2].toString());
            if (!term.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(term)) {
                continue;
            }
            if (outstanding && net.signum() == 0) {
                continue;
            }
            if (direction != null && !direction.matches(net)) {
                continue;
            }
            nets.add(new NetRow(id, name, net));
        }
        Comparator<NetRow> byName = Comparator.comparing(NetRow::name, String.CASE_INSENSITIVE_ORDER);
        Sort.Order nameOrder = pageable.getSort().getOrderFor("name");
        if (nameOrder != null && nameOrder.isDescending()) {
            byName = byName.reversed();
        }
        Comparator<NetRow> order = byNet
                ? Comparator.comparing((NetRow r) -> r.net().abs(), Comparator.reverseOrder()).thenComparing(byName)
                : byName;
        nets.sort(order.thenComparing(NetRow::id));

        List<NetRow> slice = pageable.isUnpaged()
                ? nets
                : nets.subList((int) Math.min(pageable.getOffset(), nets.size()),
                        (int) Math.min(pageable.getOffset() + pageable.getPageSize(), nets.size()));
        Map<UUID, Counterparty> byId = new HashMap<>();
        counterpartyRepository.findAllById(slice.stream().map(NetRow::id).toList())
                .forEach(cp -> byId.put(cp.getId(), cp));
        List<CounterpartyResponse> content = slice.stream()
                .map(r -> byId.get(r.id()))
                .filter(java.util.Objects::nonNull)
                .map(this::toCounterpartyResponse)
                .toList();
        return new PageImpl<>(content, pageable, nets.size());
    }

    private record NetRow(UUID id, String name, BigDecimal net) {
    }

    /** Lists the caller's counterparties; a non-blank {@code q} narrows to names containing it (case-insensitive). */
    @Transactional(readOnly = true)
    public Page<CounterpartyResponse> getCounterparties(@Nullable String q, Pageable pageable) {
        String term = q == null ? "" : q.trim();
        Page<Counterparty> page = term.isEmpty()
                ? counterpartyRepository.findAll(pageable)
                : counterpartyRepository.findByNameContainingIgnoreCase(term, pageable);
        return page.map(this::toCounterpartyResponse);
    }

    /**
     * Best counterparty for a transaction description by name-token overlap (see
     * {@link CounterpartyNameMatcher}). The list is name-ordered so ties resolve
     * deterministically.
     */
    @Transactional(readOnly = true)
    public CounterpartySuggestionResponse suggestCounterparty(@Nullable String text) {
        List<Counterparty> all = counterpartyRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
        Counterparty best = CounterpartyNameMatcher.bestMatch(text, all);
        return new CounterpartySuggestionResponse(best == null ? null : toCounterpartyResponse(best));
    }

    /**
     * The current user's counterparty with its ledger totals (as {@link #getCounterparties} lists it),
     * or empty when no such counterparty exists or it belongs to someone else.
     */
    @Transactional(readOnly = true)
    public Optional<CounterpartyResponse> findOwnedCounterparty(UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        return counterpartyRepository.findById(id)
                .filter(cp -> cp.getUser() != null && cp.getUser().getId().equals(userId))
                .map(this::toCounterpartyResponse);
    }

    /** One counterparty with the list's totals and the last-entry / expected-return fields; 404 when missing or not the caller's. */
    @Transactional(readOnly = true)
    public CounterpartyResponse getCounterparty(UUID id) {
        return toCounterpartyResponse(getOwnedCounterpartyOr404(id));
    }

    private Counterparty getOwnedCounterpartyOr404(UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        return counterpartyRepository.findById(id)
                .filter(cp -> cp.getUser() != null && cp.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Counterparty", id));
    }

    /**
     * Tiles over all people: what is owed each way (nets are kind-agnostic, as in
     * {@code netPosition}) and, per direction, the people whose expected return date
     * ({@link #expectedReturnDateOf}) falls on or before the last day of the current month (IST).
     */
    @Transactional(readOnly = true)
    public CounterpartySummaryResponse getCounterpartySummary() {
        LocalDate today = AppTime.today();
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        Map<UUID, List<Lending>> byPerson = new LinkedHashMap<>();
        for (Lending l : lendingRepository.findAllWithRefs()) {
            if (l.getCounterparty() != null) {
                byPerson.computeIfAbsent(l.getCounterparty().getId(), k -> new ArrayList<>()).add(l);
            }
        }
        BigDecimal owedToYou = BigDecimal.ZERO;
        BigDecimal youOwe = BigDecimal.ZERO;
        long owedToYouCount = 0;
        long youOweCount = 0;
        SummaryBucket due = new SummaryBucket();
        SummaryBucket back = new SummaryBucket();
        for (List<Lending> group : byPerson.values()) {
            BigDecimal net = netOf(group);
            if (net.signum() == 0) {
                continue;
            }
            LocalDate expected = expectedReturnDateOf(group);
            boolean inMonth = expected != null && !expected.isAfter(monthEnd);
            String name = group.get(0).getCounterparty().getName();
            if (net.signum() > 0) {
                owedToYou = owedToYou.add(net);
                owedToYouCount++;
                if (inMonth) back.add(net, name, expected);
            } else {
                youOwe = youOwe.add(net.abs());
                youOweCount++;
                if (inMonth) due.add(net.abs(), name, expected);
            }
        }
        return new CounterpartySummaryResponse(owedToYou, owedToYouCount, youOwe, youOweCount,
                due.toBucket(), back.toBucket());
    }

    private static final class SummaryBucket {
        BigDecimal amount = BigDecimal.ZERO;
        long count;
        String nextName;
        LocalDate nextDate;

        void add(BigDecimal value, String name, LocalDate date) {
            amount = amount.add(value);
            count++;
            if (nextDate == null || date.isBefore(nextDate)
                    || (date.equals(nextDate) && name.compareToIgnoreCase(nextName) < 0)) {
                nextDate = date;
                nextName = name;
            }
        }

        CounterpartySummaryResponse.Bucket toBucket() {
            return new CounterpartySummaryResponse.Bucket(amount, count, nextName, nextDate);
        }
    }

    /** Money out (lent-direction entries) minus money in (borrowed-direction), every kind: netPosition. */
    private static BigDecimal netOf(List<Lending> entries) {
        BigDecimal net = BigDecimal.ZERO;
        for (Lending l : entries) {
            net = l.getDirection() == LendingDirection.lent ? net.add(l.getAmount()) : net.subtract(l.getAmount());
        }
        return net;
    }

    /**
     * The person's expected return date: the earliest one among principal entries in the outstanding
     * direction, exactly the subject {@link LendingReturnNotificationService#dueObligations} picks for
     * the "money due back" reminder (asked with no upper date bound). Null when settled or none set.
     */
    private static LocalDate expectedReturnDateOf(List<Lending> entries) {
        return LendingReturnNotificationService.dueObligations(entries, LocalDate.MAX).stream()
                .findFirst()
                .map(o -> o.subject().getExpectedReturnDate())
                .orElse(null);
    }

    /** Ledger order, oldest first: entry date, then creation time, then id (stable). */
    private static final Comparator<Lending> CHRONOLOGICAL = Comparator
            .comparing(Lending::getEntryDate, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Lending::getCreatedAt, Comparator.nullsFirst(Comparator.<java.time.Instant>naturalOrder()))
            .thenComparing(Lending::getId, Comparator.nullsFirst(Comparator.<UUID>naturalOrder()));

    /**
     * The person's entries newest first, each with the balance right after it (running over every
     * entry oldest first, then paged), plus totals over all entries. 404 when missing or not the caller's.
     */
    @Transactional(readOnly = true)
    public CounterpartyLedgerResponse getLedger(UUID counterpartyId, Pageable pageable) {
        getOwnedCounterpartyOr404(counterpartyId);
        List<Lending> chronological = new ArrayList<>(lendingRepository.findByCounterpartyIdWithRefs(counterpartyId));
        chronological.sort(CHRONOLOGICAL);
        BigDecimal lent = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal running = BigDecimal.ZERO;
        List<LedgerEntryResponse> newestFirst = new ArrayList<>(chronological.size());
        for (Lending l : chronological) {
            if (l.getDirection() == LendingDirection.lent) {
                lent = lent.add(l.getAmount());
                running = running.add(l.getAmount());
            } else {
                received = received.add(l.getAmount());
                running = running.subtract(l.getAmount());
            }
            newestFirst.add(LedgerEntryResponse.from(LendingResponse.from(l), running));
        }
        Collections.reverse(newestFirst);
        List<LedgerEntryResponse> slice = pageable.isUnpaged()
                ? newestFirst
                : newestFirst.subList((int) Math.min(pageable.getOffset(), newestFirst.size()),
                        (int) Math.min(pageable.getOffset() + pageable.getPageSize(), newestFirst.size()));
        return new CounterpartyLedgerResponse(new PageImpl<>(slice, pageable, newestFirst.size()),
                new CounterpartyLedgerResponse.Totals(lent, received, lent.subtract(received)));
    }

    /** Match suggestions for every person that has at least one candidate, name order. */
    @Transactional(readOnly = true)
    public List<CounterpartyMatchSuggestionsResponse> getAllMatchSuggestions() {
        // referenced ids load lazily, once, on the first person with unlinked entries
        Set<UUID>[] referenced = new Set[1];
        return allMatchSuggestions(() -> {
            if (referenced[0] == null) {
                referenced[0] = transactionValidator.getAllReferencedTransactionIds();
            }
            return referenced[0];
        });
    }

    private List<CounterpartyMatchSuggestionsResponse> allMatchSuggestions(java.util.function.Supplier<Set<UUID>> referencedTxIds) {
        List<CounterpartyMatchSuggestionsResponse> out = new ArrayList<>();
        for (Counterparty cp : counterpartyRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))) {
            List<Lending> entries = findUnlinkedEntries(cp.getId());
            if (entries.isEmpty()) {
                continue;
            }
            List<LendingMatchSuggestionsResponse.LendingMatchSuggestion> suggestions =
                    computeSuggestions(cp.getId(), entries, referencedTxIds.get());
            if (!suggestions.isEmpty()) {
                out.add(new CounterpartyMatchSuggestionsResponse(cp.getId(), cp.getName(), suggestions));
            }
        }
        return out;
    }

    // --- Unrecorded transfers (bank transfers naming a person, no lending entry) ---

    static final int UNRECORDED_WINDOW_DAYS = 90;
    static final int UNRECORDED_CAP = 50;

    /**
     * Bank (non-card) transactions of the last 90 days that name exactly one person, are referenced by
     * nothing, sit in no link (so internal transfers drop out), reduce that person's balance and are not
     * already an entry candidate for them. Newest first, capped at 50.
     */
    @Transactional(readOnly = true)
    public List<UnrecordedTransferResponse> getUnrecordedTransfers() {
        UUID userId = UserContext.getCurrentUserId();
        LocalDate today = AppTime.today();
        Set<UUID> referenced = transactionValidator.getAllReferencedTransactionIds();

        record Person(UUID id, String name, BigDecimal net) {}
        List<Person> people = new ArrayList<>();
        for (Object[] row : counterpartyRepository.findNetPositions(userId)) {
            UUID id = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
            BigDecimal net = row[2] instanceof BigDecimal bd ? bd : new BigDecimal(row[2].toString());
            people.add(new Person(id, row[1] == null ? "" : row[1].toString(), net));
        }
        if (people.isEmpty()) {
            return List.of();
        }

        record Hit(Person person, Transaction tx, LendingDirection direction) {}
        List<Hit> hits = new ArrayList<>();
        int ambiguous = 0;
        for (Transaction tx : transactionRepository.findBankTransactionsBetween(
                userId, today.minusDays(UNRECORDED_WINDOW_DAYS), today)) {
            if (referenced.contains(tx.getId())) {
                continue;
            }
            Set<String> words = new HashSet<>(UnrecordedTransferMatcher.words(tx.getDescription()));
            words.addAll(UnrecordedTransferMatcher.words(tx.getSourcedDescription()));
            List<Person> named = people.stream()
                    .filter(p -> UnrecordedTransferMatcher.matches(p.name(), words)).toList();
            if (named.isEmpty()) {
                continue;
            }
            if (named.size() > 1) {
                ambiguous++;
                continue;
            }
            Person p = named.get(0);
            LendingDirection direction = settlementDirectionOrNull(p.net(), tx);
            if (direction != null) {
                hits.add(new Hit(p, tx, direction));
            }
        }
        if (ambiguous > 0) {
            log.info("Unrecorded transfers: {} transaction(s) name several people and were not offered", ambiguous);
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        Set<UUID> hitIds = hits.stream().map(h -> h.tx().getId()).collect(Collectors.toSet());
        Set<UUID> linked = transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(hitIds).stream()
                .flatMap(link -> link.getMembers().stream())
                .map(m -> m.getTransaction().getId())
                .collect(Collectors.toSet());
        Map<UUID, Set<UUID>> offeredAsCandidate = new HashMap<>();
        for (CounterpartyMatchSuggestionsResponse s : allMatchSuggestions(() -> referenced)) {
            Set<UUID> ids = offeredAsCandidate.computeIfAbsent(s.counterpartyId(), k -> new HashSet<>());
            s.suggestions().forEach(sg -> sg.candidates().forEach(c -> ids.add(c.id())));
        }

        return hits.stream()
                .filter(h -> !linked.contains(h.tx().getId()))
                .filter(h -> !offeredAsCandidate.getOrDefault(h.person().id(), Set.of()).contains(h.tx().getId()))
                .sorted(Comparator.comparing((Hit h) -> h.tx().getDate(), Comparator.reverseOrder())
                        .thenComparing(h -> h.tx().getId()))
                .limit(UNRECORDED_CAP)
                .map(h -> new UnrecordedTransferResponse(h.person().id(), h.person().name(),
                        TransactionResponse.from(h.tx()), h.direction()))
                .toList();
    }

    /**
     * The settlement direction when this transaction reduces {@code net} (a credit while they owe you,
     * a debit while you owe them) and its amount is within |net| plus the match tolerance; else null.
     */
    private static LendingDirection settlementDirectionOrNull(BigDecimal net, Transaction tx) {
        boolean credit = tx.getType() == TransactionType.CREDIT;
        if (credit ? net.signum() <= 0 : net.signum() >= 0) {
            return null;
        }
        if (tx.getAmount().abs().compareTo(net.abs().add(MatchingConstants.MATCH_AMOUNT_TOLERANCE)) > 0) {
            return null;
        }
        return credit ? LendingDirection.borrowed : LendingDirection.lent;
    }

    /**
     * Records the transfer as a settlement entry for the person and links the transaction, through
     * {@link #createLending}. 404 for a foreign/missing person or transaction, 409 when the transaction
     * is already referenced or linked, 400 when it would not reduce the balance or exceeds it.
     */
    public LendingResponse recordSettlement(UUID counterpartyId, UUID transactionId) {
        UUID userId = UserContext.getCurrentUserId();
        Counterparty cp = getOwnedCounterpartyOr404(counterpartyId);
        Transaction tx = transactionRepository.findById(transactionId)
                .filter(t -> t.getUser() != null && t.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));
        if (transactionValidator.isTransactionReferenced(transactionId)
                || !transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(List.of(transactionId)).isEmpty()) {
            throw new DuplicateResourceException("Transaction " + transactionId + " is already linked to a record");
        }
        BigDecimal net = netOf(lendingRepository.findByCounterparty_Id(cp.getId()));
        LendingDirection direction = settlementDirectionOrNull(net, tx);
        if (direction == null) {
            throw new ValidationException("This transaction would not reduce " + cp.getName() + "'s balance");
        }
        return createLending(new CreateLendingRequest(cp.getId(), null, direction, LendingKind.settlement,
                tx.getAmount().abs(), tx.getDate(), null, tx.getId(), null));
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
        lending.setKind(req.kind() != null ? req.kind() : LendingKind.principal);
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
        if (req.kind() != null) lending.setKind(req.kind());
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

        List<Lending> entries = findUnlinkedEntries(counterpartyId);

        if (entries.isEmpty()) {
            logMatchAttempt(counterpartyId, null, null, 0, false, "no-unlinked-entries");
            return new LendingMatchSuggestionsResponse(Collections.emptyList());
        }

        return new LendingMatchSuggestionsResponse(
                computeSuggestions(counterpartyId, entries, transactionValidator.getAllReferencedTransactionIds()));
    }

    private List<Lending> findUnlinkedEntries(UUID counterpartyId) {
        return lendingRepository.findByCounterpartyIdWithRefs(counterpartyId).stream()
                .filter(l -> l.getTransaction() == null)
                .sorted(Comparator.comparing(Lending::getEntryDate).thenComparing(Lending::getCreatedAt))
                .toList();
    }

    private List<LendingMatchSuggestionsResponse.LendingMatchSuggestion> computeSuggestions(
            UUID counterpartyId, List<Lending> entries, Set<UUID> referencedTxIds) {
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

        return suggestions;
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

    /** Gross totals split by kind: principal feeds total lent/borrowed, settlements feed the repaid figures. */
    private CounterpartyResponse toCounterpartyResponse(Counterparty cp) {
        List<Lending> entries = lendingRepository.findByCounterparty_Id(cp.getId());
        BigDecimal totalLent = BigDecimal.ZERO;
        BigDecimal totalBorrowed = BigDecimal.ZERO;
        BigDecimal repaidToYou = BigDecimal.ZERO;
        BigDecimal repaidByYou = BigDecimal.ZERO;

        for (Lending l : entries) {
            boolean settlement = l.getKind() == LendingKind.settlement;
            if (l.getDirection() == LendingDirection.lent) {
                if (settlement) repaidByYou = repaidByYou.add(l.getAmount());
                else totalLent = totalLent.add(l.getAmount());
            } else if (l.getDirection() == LendingDirection.borrowed) {
                if (settlement) repaidToYou = repaidToYou.add(l.getAmount());
                else totalBorrowed = totalBorrowed.add(l.getAmount());
            }
        }
        Lending last = entries.stream().max(CHRONOLOGICAL).orElse(null);
        return CounterpartyResponse.from(cp, totalLent, totalBorrowed, repaidToYou, repaidByYou, entries.size(),
                last == null ? null : last.getEntryDate(), last == null ? null : last.getDirection(), last == null ? null : last.getKind(),
                last == null ? null : last.getAmount(), expectedReturnDateOf(entries),
                entries.stream().map(Lending::getEntryDate).filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                (int) entries.stream().filter(l -> l.getTransaction() != null).count());
    }

    // --- Aggregates & Helper Methods ---

    /**
     * Outstanding totals across people. Each person's net is kind-agnostic — every lent-direction
     * entry (principal or settlement) minus every borrowed-direction one — so it equals
     * {@link CounterpartyResponse#netPosition()}; positive nets sum into lentOutstanding, negative
     * ones into borrowedOutstanding.
     */
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
        LocalDate today = AppTime.today();

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
