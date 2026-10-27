package com.financeos.domain.epf;

import com.financeos.api.epf.dto.EpfDismissRequest;
import com.financeos.api.epf.dto.EpfEmployerRequest;
import com.financeos.api.epf.dto.EpfEmployerResponse;
import com.financeos.api.epf.dto.EpfEntryRequest;
import com.financeos.api.epf.dto.EpfEntryResponse;
import com.financeos.api.epf.dto.EpfEntryResponse.EpfLinkedTransaction;
import com.financeos.api.epf.dto.EpfLinkRequest;
import com.financeos.api.epf.dto.EpfSummaryResponse;
import com.financeos.api.epf.dto.EpfWithdrawalOption;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.investment.FinancialYear;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * EPF: employers, the ledger entries per employer, the summary, and linking withdrawals to the bank
 * credits they landed as. EPF data is small, so each call loads the user's employers and entries once
 * and computes in memory ({@link EpfLedger}); there are no per-row queries.
 */
@Service
@Transactional
public class EpfService {

    static final int STALE_DAYS = 45;
    private static final BigDecimal PENSION_RATE = new BigDecimal("0.0833");
    private static final BigDecimal PENSION_WAGE_CEILING = new BigDecimal("15000");
    private static final BigDecimal PENSION_DIVISOR = new BigDecimal("70");

    private final EpfEmployerRepository employerRepository;
    private final EpfEntryRepository entryRepository;
    private final EpfLinkDismissalRepository dismissalRepository;
    private final EpfSuggestionService suggestionService;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceValidator referenceValidator;

    public EpfService(EpfEmployerRepository employerRepository, EpfEntryRepository entryRepository,
                      EpfLinkDismissalRepository dismissalRepository, EpfSuggestionService suggestionService,
                      UserRepository userRepository, TransactionRepository transactionRepository,
                      TransactionReferenceValidator referenceValidator) {
        this.employerRepository = employerRepository;
        this.entryRepository = entryRepository;
        this.dismissalRepository = dismissalRepository;
        this.suggestionService = suggestionService;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.referenceValidator = referenceValidator;
    }

    // ------------------------------------------------------------------ loading

    /** Everything one call needs, loaded once. */
    private record Data(UUID userId, Map<UUID, EpfEmployer> employers, List<EpfEntry> entries, EpfLedger ledger,
                        Map<UUID, EpfEntry> entriesById) {
    }

    private Data load() {
        UUID userId = currentUserId();
        Map<UUID, EpfEmployer> employers = new LinkedHashMap<>();
        for (EpfEmployer employer : employerRepository.findAllByUser_Id(userId)) {
            employers.put(employer.getId(), employer);
        }
        List<EpfEntry> entries = entryRepository.findAllForUser(userId);
        Map<UUID, EpfEntry> byId = new HashMap<>();
        for (EpfEntry entry : entries) {
            byId.put(entry.getId(), entry);
        }
        return new Data(userId, employers, entries, EpfLedger.of(entries), byId);
    }

    private static UUID currentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResourceNotFoundException("User", "current");
        }
        return userId;
    }

    // ------------------------------------------------------------------ employer status

    private record Status(EpfEmployerStatus status, LocalDate date) {
    }

    private static Status statusOf(EpfEmployer employer, List<EpfEntry> entries, EpfLedger ledger) {
        LocalDate settled = latest(entries, e -> e.getType() == EpfEntryType.WITHDRAWAL
                && e.getWithdrawalKind() == EpfWithdrawalKind.FINAL_SETTLEMENT);
        if (settled != null) {
            return new Status(EpfEmployerStatus.SETTLED, settled);
        }
        LocalDate transferredOut = latest(entries, e -> e.getType() == EpfEntryType.TRANSFER_OUT);
        if (transferredOut != null && ledger.balance(employer.getId()).total().signum() <= 0) {
            return new Status(EpfEmployerStatus.TRANSFERRED, transferredOut);
        }
        if (employer.getLeftOn() != null) {
            return new Status(EpfEmployerStatus.LEFT, null);
        }
        return new Status(EpfEmployerStatus.CURRENT, null);
    }

    private static LocalDate latest(List<EpfEntry> entries, java.util.function.Predicate<EpfEntry> filter) {
        return entries.stream().filter(filter).map(EpfEntry::getCreditedOn).max(LocalDate::compareTo).orElse(null);
    }

    private static Map<UUID, List<EpfEntry>> byEmployer(Data data) {
        Map<UUID, List<EpfEntry>> map = new HashMap<>();
        for (EpfEntry entry : data.ledger().ordered()) {
            map.computeIfAbsent(entry.getEmployer().getId(), k -> new ArrayList<>()).add(entry);
        }
        return map;
    }

    // ------------------------------------------------------------------ summary

    @Transactional(readOnly = true)
    public EpfSummaryResponse getSummary() {
        Data data = load();
        LocalDate today = AppTime.today();
        int fy = FinancialYear.startYear(today);
        LocalDate fyStart = FinancialYear.start(fy);
        LocalDate fyEnd = FinancialYear.end(fy);
        Map<UUID, List<EpfEntry>> perEmployer = byEmployer(data);
        Map<UUID, Status> statuses = new HashMap<>();
        for (EpfEmployer employer : data.employers().values()) {
            statuses.put(employer.getId(), statusOf(employer, perEmployer.getOrDefault(employer.getId(), List.of()), data.ledger()));
        }

        BigDecimal fyEmployee = BigDecimal.ZERO;
        BigDecimal fyEmployer = BigDecimal.ZERO;
        for (EpfEntry e : data.entries()) {
            if (e.getType() == EpfEntryType.CONTRIBUTION && !e.getCreditedOn().isBefore(fyStart)
                    && !e.getCreditedOn().isAfter(fyEnd)) {
                fyEmployee = fyEmployee.add(nz(e.getEmployeeAmount()));
                fyEmployer = fyEmployer.add(nz(e.getEmployerAmount()));
            }
        }
        BigDecimal balance = data.ledger().total().total();
        EpfSummaryResponse.EpfLatestContribution latest = latestContribution(data);
        return new EpfSummaryResponse(!data.employers().isEmpty() || !data.entries().isEmpty(), balance, fyEmployee,
                fyEmployer, interestEstimate(data, today, fy, balance), lastInterest(data), pension(data, today, statuses),
                staleThrough(data, today, statuses), uanLast4(data), latest == null ? null : latest.wageMonth(), latest);
    }

    /** The latest CONTRIBUTION by wage month, then credited date; null when there is none. */
    private static EpfSummaryResponse.EpfLatestContribution latestContribution(Data data) {
        return data.entries().stream()
                .filter(e -> e.getType() == EpfEntryType.CONTRIBUTION && e.getWageMonth() != null)
                .max(Comparator.comparing(EpfEntry::getWageMonth).thenComparing(EpfEntry::getCreditedOn))
                .map(e -> new EpfSummaryResponse.EpfLatestContribution(e.getEmployer().getId(), e.getWageMonth(),
                        e.getCreditedOn(), nz(e.getEmployeeAmount()), nz(e.getEmployerAmount()),
                        nz(e.getPensionAmount())))
                .orElse(null);
    }

    private static EpfSummaryResponse.EpfLastInterest lastInterest(Data data) {
        List<EpfEntry> interest = data.entries().stream()
                .filter(e -> e.getType() == EpfEntryType.INTEREST && e.getInterestFy() != null).toList();
        if (interest.isEmpty()) {
            return null;
        }
        int fy = interest.stream().mapToInt(EpfEntry::getInterestFy).max().orElseThrow();
        BigDecimal amount = BigDecimal.ZERO;
        LocalDate creditedOn = null;
        for (EpfEntry e : interest) {
            if (e.getInterestFy() == fy) {
                amount = amount.add(nz(e.getEmployeeAmount())).add(nz(e.getEmployerAmount()));
                if (creditedOn == null || e.getCreditedOn().isAfter(creditedOn)) {
                    creditedOn = e.getCreditedOn();
                }
            }
        }
        return new EpfSummaryResponse.EpfLastInterest(fy, amount, EpfInterestRates.rateFor(fy), creditedOn);
    }

    private static EpfSummaryResponse.EpfInterestEstimate interestEstimate(Data data, LocalDate today, int fy, BigDecimal balance) {
        boolean credited = data.entries().stream()
                .anyMatch(e -> e.getType() == EpfEntryType.INTEREST && e.getInterestFy() != null && e.getInterestFy() == fy);
        if (credited || balance.signum() <= 0) {
            return null;
        }
        BigDecimal declared = EpfInterestRates.rateFor(fy);
        int rateFy = fy;
        BigDecimal rate = declared;
        if (rate == null) {
            EpfInterestRates.Declared latest = EpfInterestRates.latestDeclared();
            rate = latest.rate();
            rateFy = latest.fy();
        }
        YearMonth last = YearMonth.from(today).minusMonths(1);
        if (last.isBefore(YearMonth.of(fy, 4))) {
            return null; // April: no month has closed in this financial year yet
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (UUID employerId : data.employers().keySet()) {
            for (YearMonth month = YearMonth.of(fy, 4); !month.isAfter(last); month = month.plusMonths(1)) {
                BigDecimal monthEnd = data.ledger().balanceAtEndOfMonth(employerId, month).total();
                sum = sum.add(monthEnd.multiply(rate).divide(new BigDecimal("1200"), 10, RoundingMode.HALF_UP));
            }
        }
        return new EpfSummaryResponse.EpfInterestEstimate(fy, sum.setScale(0, RoundingMode.HALF_UP), rate, rateFy);
    }

    private static EpfSummaryResponse.EpfPension pension(Data data, LocalDate today, Map<UUID, Status> statuses) {
        EpfEntry latestWithPension = data.ledger().ordered().stream()
                .filter(e -> e.getType() == EpfEntryType.CONTRIBUTION && nz(e.getPensionAmount()).signum() > 0)
                .max(Comparator.comparing(EpfEntry::getCreditedOn)
                        .thenComparing(e -> e.getWageMonth() == null ? LocalDate.MIN : e.getWageMonth()))
                .orElse(null);
        if (latestWithPension == null) {
            return null;
        }
        Set<UUID> paidOut = new HashSet<>();
        for (EpfEntry e : data.entries()) {
            if (e.getType() == EpfEntryType.WITHDRAWAL && e.getWithdrawalKind() == EpfWithdrawalKind.PENSION) {
                paidOut.add(e.getEmployer().getId());
            }
        }
        Map<UUID, List<EpfEntry>> perEmployer = byEmployer(data);
        long months = 0;
        for (EpfEmployer employer : data.employers().values()) {
            if (paidOut.contains(employer.getId())) {
                continue;
            }
            List<EpfEntry> contributions = perEmployer.getOrDefault(employer.getId(), List.of()).stream()
                    .filter(e -> e.getType() == EpfEntryType.CONTRIBUTION && e.getWageMonth() != null).toList();
            LocalDate start = employer.getJoinedOn() != null ? employer.getJoinedOn()
                    : contributions.stream().map(EpfEntry::getWageMonth).min(LocalDate::compareTo).orElse(null);
            if (start == null) {
                continue;
            }
            LocalDate end;
            if (employer.getLeftOn() != null) {
                end = employer.getLeftOn().plusDays(1); // the last day counts, so Apr 1 to Mar 31 is 12 months
            } else if (statuses.get(employer.getId()).status() == EpfEmployerStatus.CURRENT) {
                end = today;
            } else if (!contributions.isEmpty()) {
                end = contributions.stream().map(EpfEntry::getWageMonth).max(LocalDate::compareTo).orElseThrow().plusMonths(1);
            } else {
                continue;
            }
            months += Math.max(0, ChronoUnit.MONTHS.between(start, end));
        }
        int serviceMonths = (int) months;
        int roundedYears = serviceMonths / 12 + (serviceMonths % 12 >= 6 ? 1 : 0);
        BigDecimal wage = nz(latestWithPension.getPensionAmount()).divide(PENSION_RATE, 4, RoundingMode.HALF_UP)
                .min(PENSION_WAGE_CEILING);
        BigDecimal estimate = wage.multiply(BigDecimal.valueOf(roundedYears)).divide(PENSION_DIVISOR, 0, RoundingMode.HALF_UP);
        return new EpfSummaryResponse.EpfPension(nz(latestWithPension.getPensionAmount()), serviceMonths, estimate,
                roundedYears >= 10);
    }

    private static LocalDate staleThrough(Data data, LocalDate today, Map<UUID, Status> statuses) {
        LocalDate cutoff = today.minusDays(STALE_DAYS);
        Map<UUID, List<EpfEntry>> perEmployer = byEmployer(data);
        LocalDate earliest = null;
        for (EpfEmployer employer : data.employers().values()) {
            if (statuses.get(employer.getId()).status() != EpfEmployerStatus.CURRENT) {
                continue;
            }
            EpfEntry latest = perEmployer.getOrDefault(employer.getId(), List.of()).stream()
                    .filter(e -> e.getType() == EpfEntryType.CONTRIBUTION)
                    .max(Comparator.comparing(EpfEntry::getCreditedOn)).orElse(null);
            if (latest != null && latest.getCreditedOn().isBefore(cutoff) && latest.getWageMonth() != null
                    && (earliest == null || latest.getWageMonth().isBefore(earliest))) {
                earliest = latest.getWageMonth();
            }
        }
        return earliest;
    }

    private static String uanLast4(Data data) {
        for (EpfEmployer employer : data.employers().values()) {
            String uan = employer.getUan();
            if (uan != null && uan.length() >= 4) {
                return uan.substring(uan.length() - 4);
            }
        }
        return null;
    }

    /** The EPF balance over all employers, or null when the user has no employers (net worth shows no row then). */
    @Transactional(readOnly = true)
    public BigDecimal getTotalBalanceOrNull() {
        Data data = load();
        return data.employers().isEmpty() ? null : data.ledger().total().total();
    }

    // ------------------------------------------------------------------ employers

    @Transactional(readOnly = true)
    public List<EpfEmployerResponse> getEmployers() {
        Data data = load();
        Map<UUID, List<EpfEntry>> perEmployer = byEmployer(data);
        List<EpfEmployerResponse> out = new ArrayList<>();
        Map<UUID, LocalDate> sortDate = new HashMap<>();
        for (EpfEmployer employer : data.employers().values()) {
            List<EpfEntry> entries = perEmployer.getOrDefault(employer.getId(), List.of());
            out.add(employerResponse(employer, entries, data));
            LocalDate date = employer.getJoinedOn() != null ? employer.getJoinedOn()
                    : entries.stream().map(EpfEntry::getCreditedOn).min(LocalDate::compareTo).orElse(LocalDate.MIN);
            sortDate.put(employer.getId(), date);
        }
        out.sort(Comparator
                .comparing((EpfEmployerResponse r) -> r.status() == EpfEmployerStatus.CURRENT ? 0 : 1)
                .thenComparing((EpfEmployerResponse r) -> sortDate.get(r.id()), Comparator.reverseOrder())
                .thenComparing(EpfEmployerResponse::name));
        return out;
    }

    private static EpfEmployerResponse employerResponse(EpfEmployer e, List<EpfEntry> entries, Data data) {
        EpfLedger ledger = data.ledger();
        Status status = statusOf(e, entries, ledger);
        EpfLedger.Balance balance = ledger.balance(e.getId());
        BigDecimal transferredOut = null;
        String transferredTo = null;
        boolean pensionWithdrawn = false;
        for (EpfEntry entry : entries) { // ledger order, oldest first
            if (entry.getType() == EpfEntryType.TRANSFER_OUT) {
                transferredOut = (transferredOut == null ? BigDecimal.ZERO : transferredOut).add(EpfLedger.gross(entry));
                EpfEntry counterpart = entry.getCounterpartEntryId() == null ? null
                        : data.entriesById().get(entry.getCounterpartEntryId());
                if (counterpart != null) {
                    transferredTo = counterpart.getEmployer().getName();
                }
            } else if (entry.getType() == EpfEntryType.WITHDRAWAL
                    && entry.getWithdrawalKind() == EpfWithdrawalKind.PENSION) {
                pensionWithdrawn = true;
            }
        }
        return new EpfEmployerResponse(e.getId(), e.getName(), e.getMemberId(), e.getEstablishmentId(), e.getUan(),
                e.getJoinedOn(), e.getLeftOn(), status.status(), status.date(), balance.total(), balance.employee(),
                balance.employer(), transferredOut, transferredTo, pensionWithdrawn);
    }

    public EpfEmployerResponse createEmployer(EpfEmployerRequest request) {
        EpfEmployer employer = new EpfEmployer();
        employer.setUser(userRepository.getReferenceById(currentUserId()));
        applyEmployer(employer, request);
        return employerResponse(employerRepository.save(employer).getId());
    }

    public EpfEmployerResponse updateEmployer(UUID id, EpfEmployerRequest request) {
        EpfEmployer employer = ownedEmployer(id);
        applyEmployer(employer, request);
        employerRepository.save(employer);
        return employerResponse(id);
    }

    private EpfEmployerResponse employerResponse(UUID id) {
        employerRepository.flush();
        Data data = load();
        EpfEmployer employer = data.employers().get(id);
        return employerResponse(employer, byEmployer(data).getOrDefault(id, List.of()), data);
    }

    private void applyEmployer(EpfEmployer employer, EpfEmployerRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ValidationException("Employer name is required.");
        }
        if (name.length() > 200) {
            throw new ValidationException("Employer name is too long.");
        }
        String memberId = blankToNull(request.memberId());
        if (memberId != null && memberId.length() > 40) {
            throw new ValidationException("Member ID is too long.");
        }
        if (request.joinedOn() != null && request.leftOn() != null && request.leftOn().isBefore(request.joinedOn())) {
            throw new ValidationException("Left date can't be before the joined date.");
        }
        if (memberId != null) {
            for (EpfEmployer other : employerRepository.findAllByUser_Id(currentUserId())) {
                if (!other.getId().equals(employer.getId()) && memberId.equalsIgnoreCase(other.getMemberId())) {
                    throw new ValidationException("Another employer already uses this member ID.");
                }
            }
        }
        employer.setName(name);
        employer.setMemberId(memberId);
        employer.setJoinedOn(request.joinedOn());
        employer.setLeftOn(request.leftOn());
    }

    // ------------------------------------------------------------------ entries: reads

    @Transactional(readOnly = true)
    public List<Integer> getFinancialYears(UUID employerId) {
        if (employerId != null) {
            ownedEmployer(employerId);
        }
        TreeSet<Integer> years = new TreeSet<>(Collections.reverseOrder());
        for (EpfEntry e : load().entries()) {
            if (employerId == null || e.getEmployer().getId().equals(employerId)) {
                years.add(FinancialYear.startYear(e.getCreditedOn()));
            }
        }
        return new ArrayList<>(years);
    }

    @Transactional(readOnly = true)
    public Page<EpfEntryResponse> getEntries(UUID employerId, Integer fy, Pageable pageable) {
        if (employerId != null) {
            ownedEmployer(employerId);
        }
        Data data = load();
        List<EpfEntry> newestFirst = new ArrayList<>(data.ledger().ordered());
        Collections.reverse(newestFirst);
        LocalDate from = fy == null ? null : FinancialYear.start(fy);
        LocalDate to = fy == null ? null : FinancialYear.end(fy);
        List<EpfEntry> filtered = newestFirst.stream()
                .filter(e -> employerId == null || e.getEmployer().getId().equals(employerId))
                .filter(e -> from == null || (!e.getCreditedOn().isBefore(from) && !e.getCreditedOn().isAfter(to)))
                .toList();
        int start = (int) Math.min(pageable.getOffset(), filtered.size());
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<EpfEntry> page = filtered.subList(start, end);
        Map<UUID, Transaction> suggestions = suggestionService.suggest(data.userId(), data.entries(), page);
        List<EpfEntryResponse> content = page.stream().map(e -> toResponse(e, data, suggestions.get(e.getId()))).toList();
        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public EpfEntryResponse getEntry(UUID id) {
        return entryResponse(id);
    }

    private EpfEntryResponse entryResponse(UUID id) {
        entryRepository.flush();
        Data data = load();
        EpfEntry entry = data.entriesById().get(id);
        if (entry == null) {
            throw new ResourceNotFoundException("EPF entry", id);
        }
        return toResponse(entry, data, suggestionService.suggest(data.userId(), data.entries(), List.of(entry)).get(id));
    }

    private EpfEntryResponse toResponse(EpfEntry e, Data data, Transaction suggestion) {
        boolean withdrawal = e.getType() == EpfEntryType.WITHDRAWAL;
        EpfEmployer counterpartEmployer = null;
        if (e.getCounterpartEntryId() != null) {
            EpfEntry counterpart = data.entriesById().get(e.getCounterpartEntryId());
            counterpartEmployer = counterpart == null ? null : counterpart.getEmployer();
        }
        return new EpfEntryResponse(e.getId(), e.getEmployer().getId(), e.getEmployer().getName(), e.getType(),
                e.getWithdrawalKind(), e.getWageMonth(), e.getCreditedOn(), e.getInterestFy(), nz(e.getEmployeeAmount()),
                nz(e.getEmployerAmount()), nz(e.getPensionAmount()), e.getTdsAmount(),
                withdrawal ? EpfLedger.gross(e) : null, withdrawal ? EpfLedger.received(e) : null, e.getNote(),
                e.getParticulars(), e.getSource(), e.getImportedAt(),
                counterpartEmployer == null ? null : counterpartEmployer.getId(),
                counterpartEmployer == null ? null : counterpartEmployer.getName(),
                linked(e.getTransaction()), linked(suggestion), data.ledger().runningBalance(e).total(),
                e.getType() == EpfEntryType.INTEREST && e.getInterestFy() != null
                        ? EpfInterestRates.rateFor(e.getInterestFy()) : null);
    }

    private static EpfLinkedTransaction linked(Transaction t) {
        if (t == null) {
            return null;
        }
        String description = t.getDescription() != null && !t.getDescription().isBlank() ? t.getDescription()
                : t.getSourcedDescription();
        return new EpfLinkedTransaction(t.getId(), t.getDate(), description,
                t.getAccount() == null ? null : t.getAccount().getName(), t.getAmount());
    }

    // ------------------------------------------------------------------ entries: writes

    public EpfEntryResponse createEntry(EpfEntryRequest r) {
        check(r);
        EpfEmployer employer = ownedEmployer(r.employerId());
        if (r.type() == EpfEntryType.TRANSFER_OUT) {
            throw new ValidationException("A transfer out is created from the transfer in on the other employer.");
        }
        EpfEmployer from = fromEmployer(r, employer.getId());
        if (r.transactionId() != null && r.type() != EpfEntryType.WITHDRAWAL) {
            throw new ValidationException("Only a withdrawal can be linked to a bank transaction.");
        }
        EpfEntry entry = new EpfEntry();
        entry.setUser(userRepository.getReferenceById(currentUserId()));
        entry.setEmployer(employer);
        entry.setSource(EpfEntrySource.MANUAL);
        apply(entry, r);
        if (r.transactionId() != null) {
            entry.setTransaction(linkable(r.transactionId(), entry));
        }
        saveLinked(entry);
        if (from != null) {
            EpfEntry out = newOut(entry, from);
            entryRepository.save(out);
            entry.setCounterpartEntryId(out.getId());
            out.setCounterpartEntryId(entry.getId());
        }
        return entryResponse(entry.getId());
    }

    public EpfEntryResponse updateEntry(UUID id, EpfEntryRequest r) {
        EpfEntry entry = ownedEntry(id);
        check(r);
        EpfEmployer employer = ownedEmployer(r.employerId());
        if (entry.getSource() == EpfEntrySource.PASSBOOK) {
            assertImportedUnchanged(entry, r);
            if (entry.getType() == EpfEntryType.WITHDRAWAL) {
                entry.setTdsAmount(r.tdsAmount());
            }
            entry.setNote(note(r.note()));
            entryRepository.save(entry);
            return entryResponse(id);
        }
        if (r.type() == EpfEntryType.TRANSFER_OUT && entry.getType() != EpfEntryType.TRANSFER_OUT) {
            throw new ValidationException("A transfer out is created from the transfer in on the other employer.");
        }
        if (entry.getType() == EpfEntryType.TRANSFER_OUT && r.type() != EpfEntryType.TRANSFER_OUT) {
            throw new ValidationException("A transfer out can't change its type.");
        }
        EpfEmployer from = fromEmployer(r, employer.getId());
        EpfEntry counterpart = entry.getCounterpartEntryId() == null ? null : ownedEntryOrNull(entry.getCounterpartEntryId());
        assertCounterpartEditable(counterpart);

        entry.setEmployer(employer);
        apply(entry, r);
        if (r.type() != EpfEntryType.WITHDRAWAL) {
            entry.setTransaction(null);
        }
        if (entry.getType() == EpfEntryType.TRANSFER_OUT) {
            if (counterpart != null) {
                syncCounterpart(counterpart, entry, counterpart.getEmployer());
            }
        } else if (entry.getType() == EpfEntryType.TRANSFER_IN) {
            if (counterpart != null && from == null) {
                entry.setCounterpartEntryId(null);
                entryRepository.delete(counterpart);
            } else if (counterpart != null) {
                syncCounterpart(counterpart, entry, from);
                entryRepository.save(counterpart);
            } else if (from != null) {
                entryRepository.save(entry);
                EpfEntry out = newOut(entry, from);
                entryRepository.save(out);
                entry.setCounterpartEntryId(out.getId());
                out.setCounterpartEntryId(entry.getId());
            }
        } else if (counterpart != null) {
            entry.setCounterpartEntryId(null);
            entryRepository.delete(counterpart);
        }
        entryRepository.save(entry);
        return entryResponse(id);
    }

    public void deleteEntry(UUID id) {
        EpfEntry entry = ownedEntry(id);
        if (entry.getSource() == EpfEntrySource.PASSBOOK) {
            throw new ValidationException("Imported entries can't be deleted. Upload a newer passbook to change them.");
        }
        if (entry.getCounterpartEntryId() != null) {
            EpfEntry counterpart = ownedEntryOrNull(entry.getCounterpartEntryId());
            assertCounterpartEditable(counterpart);
            if (counterpart != null) {
                entryRepository.delete(counterpart);
            }
        }
        entryRepository.delete(entry);
    }

    private static void assertCounterpartEditable(EpfEntry counterpart) {
        if (counterpart != null && counterpart.getSource() == EpfEntrySource.PASSBOOK) {
            throw new ValidationException("The other side of this transfer came from a passbook and can't change.");
        }
    }

    private EpfEntry newOut(EpfEntry in, EpfEmployer from) {
        EpfEntry out = new EpfEntry();
        out.setUser(in.getUser());
        out.setSource(EpfEntrySource.MANUAL);
        out.setType(EpfEntryType.TRANSFER_OUT);
        syncCounterpart(out, in, from);
        return out;
    }

    /** Makes {@code target} the mirror of {@code source}: same date, amounts and note, on {@code employer}. */
    private static void syncCounterpart(EpfEntry target, EpfEntry source, EpfEmployer employer) {
        if (target.getSource() == EpfEntrySource.PASSBOOK) {
            return; // an imported row is never rewritten from the other side
        }
        target.setEmployer(employer);
        target.setCreditedOn(source.getCreditedOn());
        target.setEmployeeAmount(source.getEmployeeAmount());
        target.setEmployerAmount(source.getEmployerAmount());
        target.setPensionAmount(BigDecimal.ZERO);
        target.setNote(source.getNote());
        target.setCounterpartEntryId(source.getId());
    }

    /** Request rules that do not need the database. */
    private static void check(EpfEntryRequest r) {
        if (r.type() == null) {
            throw new ValidationException("Type is required.");
        }
        if (r.employerId() == null) {
            throw new ValidationException("Employer is required.");
        }
        if (r.creditedOn() == null) {
            throw new ValidationException("Credited date is required.");
        }
        BigDecimal employee = nz(r.employeeAmount());
        BigDecimal employer = nz(r.employerAmount());
        BigDecimal pension = nz(r.pensionAmount());
        if (employee.signum() < 0 || employer.signum() < 0 || pension.signum() < 0
                || (r.tdsAmount() != null && r.tdsAmount().signum() < 0)) {
            throw new ValidationException("Amounts can't be negative.");
        }
        switch (r.type()) {
            case CONTRIBUTION -> {
                if (r.wageMonth() == null) {
                    throw new ValidationException("Wage month is required for a contribution.");
                }
            }
            case INTEREST -> {
                if (r.interestFy() == null) {
                    throw new ValidationException("Financial year is required for interest.");
                }
            }
            case WITHDRAWAL -> {
                if (r.withdrawalKind() == null) {
                    throw new ValidationException("Withdrawal kind is required.");
                }
                BigDecimal gross;
                if (r.withdrawalKind() == EpfWithdrawalKind.PENSION) {
                    if (pension.signum() <= 0) {
                        throw new ValidationException("A pension withdrawal needs a pension amount.");
                    }
                    if (employee.signum() != 0 || employer.signum() != 0) {
                        throw new ValidationException("A pension withdrawal has no employee or employer amount.");
                    }
                    gross = pension;
                } else {
                    gross = employee.add(employer);
                    if (gross.signum() <= 0) {
                        throw new ValidationException("A withdrawal needs an employee or employer amount.");
                    }
                }
                if (r.tdsAmount() != null && r.tdsAmount().compareTo(gross) >= 0) {
                    throw new ValidationException("TDS must be less than the withdrawal amount.");
                }
            }
            default -> {
            }
        }
        if (r.type() != EpfEntryType.WITHDRAWAL && r.tdsAmount() != null && r.tdsAmount().signum() != 0) {
            throw new ValidationException("TDS applies to withdrawals only.");
        }
        if (r.note() != null && r.note().trim().length() > 500) {
            throw new ValidationException("Note is too long.");
        }
    }

    private static void apply(EpfEntry e, EpfEntryRequest r) {
        e.setType(r.type());
        e.setWithdrawalKind(r.type() == EpfEntryType.WITHDRAWAL ? r.withdrawalKind() : null);
        e.setWageMonth(r.type() == EpfEntryType.CONTRIBUTION ? r.wageMonth().withDayOfMonth(1) : null);
        e.setCreditedOn(r.creditedOn());
        e.setInterestFy(r.type() == EpfEntryType.INTEREST ? r.interestFy() : null);
        e.setEmployeeAmount(nz(r.employeeAmount()));
        e.setEmployerAmount(nz(r.employerAmount()));
        boolean pensionAmount = r.type() == EpfEntryType.CONTRIBUTION
                || (r.type() == EpfEntryType.WITHDRAWAL && r.withdrawalKind() == EpfWithdrawalKind.PENSION);
        e.setPensionAmount(pensionAmount ? nz(r.pensionAmount()) : BigDecimal.ZERO);
        e.setTdsAmount(r.type() == EpfEntryType.WITHDRAWAL ? r.tdsAmount() : null);
        e.setNote(note(r.note()));
    }

    private void assertImportedUnchanged(EpfEntry e, EpfEntryRequest r) {
        boolean same = r.type() == e.getType()
                && r.employerId().equals(e.getEmployer().getId())
                && r.withdrawalKind() == e.getWithdrawalKind()
                && Objects.equals(r.type() == EpfEntryType.CONTRIBUTION && r.wageMonth() != null
                        ? r.wageMonth().withDayOfMonth(1) : r.wageMonth(), e.getWageMonth())
                && r.creditedOn().equals(e.getCreditedOn())
                && Objects.equals(r.interestFy(), e.getInterestFy())
                && sameAmount(r.employeeAmount(), e.getEmployeeAmount())
                && sameAmount(r.employerAmount(), e.getEmployerAmount())
                && sameAmount(r.pensionAmount(), e.getPensionAmount());
        if (!same) {
            throw new ValidationException("Imported entries can't change. Upload a newer passbook to change them.");
        }
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return nz(a).compareTo(nz(b)) == 0;
    }

    private EpfEmployer fromEmployer(EpfEntryRequest r, UUID employerId) {
        if (r.fromEmployerId() == null) {
            return null;
        }
        if (r.type() != EpfEntryType.TRANSFER_IN) {
            throw new ValidationException("Only a transfer in has a source employer.");
        }
        if (r.fromEmployerId().equals(employerId)) {
            throw new ValidationException("A transfer needs two different employers.");
        }
        return ownedEmployer(r.fromEmployerId());
    }

    // ------------------------------------------------------------------ linking

    public EpfEntryResponse linkTransaction(UUID entryId, EpfLinkRequest request) {
        EpfEntry entry = ownedEntry(entryId);
        if (request == null || request.transactionId() == null) {
            throw new ValidationException("transactionId is required.");
        }
        if (entry.getType() != EpfEntryType.WITHDRAWAL) {
            throw new ValidationException("Only a withdrawal can be linked to a bank transaction.");
        }
        Transaction transaction = linkable(request.transactionId(), entry);
        entry.setTransaction(transaction);
        if (request.recordTds() && EpfLedger.gross(entry).compareTo(transaction.getAmount()) > 0) {
            entry.setTdsAmount(EpfLedger.gross(entry).subtract(transaction.getAmount()));
        }
        saveLinked(entry);
        return entryResponse(entryId);
    }

    /** Saves and flushes so the unique transaction link surfaces a lost race as the usual "already linked" error. */
    private void saveLinked(EpfEntry entry) {
        try {
            entryRepository.saveAndFlush(entry);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ValidationException("This transaction is already linked to another EPF entry.");
        }
    }

    public void unlinkTransaction(UUID entryId) {
        EpfEntry entry = ownedEntry(entryId);
        if (entry.getTransaction() != null) {
            entry.setTransaction(null);
            entryRepository.save(entry);
        }
    }

    public void dismissSuggestion(UUID entryId, EpfDismissRequest request) {
        EpfEntry entry = ownedEntry(entryId);
        if (request == null || request.transactionId() == null) {
            throw new ValidationException("transactionId is required.");
        }
        Transaction transaction = ownedTransaction(request.transactionId());
        if (dismissalRepository.existsByEntry_IdAndTransaction_Id(entryId, transaction.getId())) {
            return;
        }
        EpfLinkDismissal dismissal = new EpfLinkDismissal();
        dismissal.setUser(entry.getUser());
        dismissal.setEntry(entry);
        dismissal.setTransaction(transaction);
        dismissalRepository.save(dismissal);
    }

    /** A credit of the user's, not linked to another EPF entry nor to a loan, lending or dividend record. */
    private Transaction linkable(UUID transactionId, EpfEntry self) {
        Transaction transaction = ownedTransaction(transactionId);
        if (transaction.getType() != TransactionType.CREDIT) {
            throw new ValidationException("A withdrawal can only be linked to money coming in.");
        }
        boolean takenByOther = entryRepository.findWithEmployerByTransactionIdIn(List.of(transactionId)).stream()
                .anyMatch(e -> !e.getId().equals(self.getId()));
        if (takenByOther) {
            throw new ValidationException("This transaction is already linked to another EPF entry.");
        }
        if (referenceValidator.isReferencedByLoan(transactionId) || referenceValidator.isReferencedByLending(transactionId)
                || referenceValidator.isReferencedByDividend(transactionId)) {
            throw new ValidationException("This transaction is already linked to a loan, lending or dividend record.");
        }
        return transaction;
    }

    // ------------------------------------------------------------------ withdrawals for a transaction

    @Transactional(readOnly = true)
    public List<EpfWithdrawalOption> getWithdrawalOptions(UUID forTransactionId) {
        Transaction credit = forTransactionId == null ? null : ownedTransaction(forTransactionId);
        Data data = load();
        List<EpfEntry> unlinked = data.entries().stream()
                .filter(e -> e.getType() == EpfEntryType.WITHDRAWAL && e.getTransaction() == null).toList();
        Map<UUID, Transaction> suggestions = suggestionService.suggest(data.userId(), data.entries(), unlinked);
        List<EpfWithdrawalOption> options = new ArrayList<>();
        for (EpfEntry e : unlinked) {
            boolean suggested = credit != null && EpfSuggestionMatcher.fits(e, credit);
            BigDecimal tdsGap = null;
            if (credit != null && credit.getType() == TransactionType.CREDIT && nz(e.getTdsAmount()).signum() == 0) {
                BigDecimal gross = EpfLedger.gross(e);
                BigDecimal gap = gross.subtract(credit.getAmount());
                if (gap.signum() > 0 && gap.compareTo(gross) < 0) {
                    tdsGap = gap;
                }
            }
            options.add(new EpfWithdrawalOption(toResponse(e, data, suggestions.get(e.getId())), suggested, tdsGap));
        }
        options.sort(Comparator
                .comparing((EpfWithdrawalOption o) -> o.suggested() ? 0 : 1)
                .thenComparing(o -> o.entry().creditedOn(), Comparator.reverseOrder()));
        return options;
    }

    // ------------------------------------------------------------------ ownership

    private EpfEmployer ownedEmployer(UUID id) {
        return employerRepository.findByIdAndUser_Id(id, currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("EPF employer", id));
    }

    private EpfEntry ownedEntry(UUID id) {
        EpfEntry entry = ownedEntryOrNull(id);
        if (entry == null) {
            throw new ResourceNotFoundException("EPF entry", id);
        }
        return entry;
    }

    private EpfEntry ownedEntryOrNull(UUID id) {
        return entryRepository.findById(id)
                .filter(e -> e.getUser() != null && e.getUser().getId().equals(currentUserId()))
                .orElse(null);
    }

    private Transaction ownedTransaction(UUID id) {
        return transactionRepository.findById(id)
                .filter(t -> t.getUser() != null && t.getUser().getId().equals(currentUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    // ------------------------------------------------------------------ helpers

    private static String note(String note) {
        return blankToNull(note);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
