package com.financeos.domain.nps;

import com.financeos.api.nps.dto.NpsAccountRequest;
import com.financeos.api.nps.dto.NpsAllocationRequest;
import com.financeos.api.nps.dto.NpsEntryRequest;
import com.financeos.api.nps.dto.NpsEntryResponse;
import com.financeos.api.nps.dto.NpsSchemeDetailResponse;
import com.financeos.api.nps.dto.NpsSchemeResponse;
import com.financeos.api.nps.dto.NpsSummaryResponse;
import com.financeos.domain.nps.gmail.NpsGmailDetections;
import com.financeos.domain.nps.gmail.NpsGmailHeldQueue;
import com.financeos.core.exception.DuplicateResourceException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.epf.EpfEmployer;
import com.financeos.domain.epf.EpfEmployerRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.investment.FinancialYear;
import com.financeos.domain.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * NPS: accounts (one per PRAN), their allocation, the unit ledger, the summary, and the scheme catalog for
 * pickers. A call loads the user's accounts and entries once and computes in memory ({@link NpsCalculator});
 * every query is scoped to the current user, and another user's account or entry is a 404.
 */
@Service
@Transactional
public class NpsService {

    static final int NAV_STALE_DAYS = 4;
    static final int STATEMENT_STALE_DAYS = 45;
    private static final Pattern PRAN = Pattern.compile("\\d{12}");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final NpsAccountRepository accountRepository;
    private final NpsEntryRepository entryRepository;
    private final NpsAllocationRepository allocationRepository;
    private final NpsSchemeRepository schemeRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;
    private final EpfEmployerRepository employerRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NpsStatementRepository statementRepository;
    private final NpsGmailDetections gmailDetections;
    private final NpsGmailHeldQueue heldQueue;
    private final NpsStatementImportService statementImportService;
    private final NpsHistoryBackfill historyBackfill;

    public NpsService(NpsAccountRepository accountRepository, NpsEntryRepository entryRepository,
                      NpsAllocationRepository allocationRepository, NpsSchemeRepository schemeRepository,
                      InstrumentRepository instrumentRepository, InstrumentPriceRepository priceRepository,
                      EpfEmployerRepository employerRepository, UserRepository userRepository,
                      ApplicationEventPublisher eventPublisher, NpsStatementRepository statementRepository,
                      NpsGmailDetections gmailDetections, NpsGmailHeldQueue heldQueue,
                      NpsStatementImportService statementImportService, NpsHistoryBackfill historyBackfill) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.allocationRepository = allocationRepository;
        this.schemeRepository = schemeRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
        this.employerRepository = employerRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.statementRepository = statementRepository;
        this.gmailDetections = gmailDetections;
        this.heldQueue = heldQueue;
        this.statementImportService = statementImportService;
        this.historyBackfill = historyBackfill;
    }

    private static UUID currentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResourceNotFoundException("User", "current");
        }
        return userId;
    }

    // ------------------------------------------------------------------ catalog + prices

    /** Scheme code to its catalog row, name and latest feed NAV, loaded in three queries. */
    private record Catalog(Map<String, NpsScheme> schemes, Map<UUID, Instrument> instruments,
                           Map<UUID, InstrumentPrice> navs) {

        String name(String code) {
            NpsScheme scheme = schemes.get(code);
            Instrument instrument = scheme == null ? null : instruments.get(scheme.getInstrumentId());
            return instrument != null ? instrument.getName() : code;
        }

        InstrumentPrice price(String code) {
            NpsScheme scheme = schemes.get(code);
            return scheme == null ? null : navs.get(scheme.getInstrumentId());
        }
    }

    private Catalog catalog(Collection<String> codes) {
        if (codes.isEmpty()) {
            return new Catalog(Map.of(), Map.of(), Map.of());
        }
        Map<String, NpsScheme> schemes = new HashMap<>();
        for (NpsScheme s : schemeRepository.findAllById(new LinkedHashSet<>(codes))) {
            schemes.put(s.getSchemeCode(), s);
        }
        return catalog(schemes);
    }

    private Catalog catalog(Map<String, NpsScheme> schemes) {
        List<UUID> instrumentIds = schemes.values().stream().map(NpsScheme::getInstrumentId).toList();
        Map<UUID, Instrument> instruments = new HashMap<>();
        Map<UUID, InstrumentPrice> navs = new HashMap<>();
        if (!instrumentIds.isEmpty()) {
            instrumentRepository.findAllById(instrumentIds).forEach(i -> instruments.put(i.getId(), i));
            for (InstrumentPrice p : priceRepository.findLatestFeedRows(instrumentIds)) {
                navs.merge(p.getInstrument().getId(), p, (a, b) -> a.getAsOf().isAfter(b.getAsOf()) ? a : b);
            }
        }
        return new Catalog(schemes, instruments, navs);
    }

    // ------------------------------------------------------------------ loading

    private record Data(UUID userId, List<NpsAccount> accounts, Map<UUID, NpsAccount> accountsById,
                        List<NpsEntry> entries) {
    }

    private Data load() {
        UUID userId = currentUserId();
        List<NpsAccount> accounts = accountRepository.findAllByUser_Id(userId);
        Map<UUID, NpsAccount> byId = new LinkedHashMap<>();
        accounts.forEach(a -> byId.put(a.getId(), a));
        return new Data(userId, accounts, byId, entryRepository.findAllForUser(userId));
    }

    private NpsAccount ownedAccount(UUID id) {
        if (id == null) {
            throw new ValidationException("An account is required.");
        }
        return accountRepository.findByIdAndUser_Id(id, currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("NPS account", id));
    }

    // ------------------------------------------------------------------ summary

    @Transactional(readOnly = true)
    public NpsSummaryResponse getSummary() {
        return buildSummary(load());
    }

    private NpsSummaryResponse buildSummary(Data data) {
        LocalDate today = AppTime.today();
        List<NpsCalculator.Holding> held = NpsCalculator.holdings(data.entries());
        List<NpsAllocation> allocations = data.accounts().isEmpty() ? List.of()
                : allocationRepository.findForAccounts(data.accountsById().keySet());
        Set<String> codes = new LinkedHashSet<>();
        held.forEach(h -> codes.add(h.schemeCode()));
        allocations.forEach(a -> codes.add(a.getSchemeCode()));
        Catalog catalog = catalog(codes);
        List<NpsStatement> statements = statementRepository.findAllLightForUser(data.userId());
        Map<UUID, LocalDate> statementEnd = latestStatementEnds(statements);
        Map<NpsCalculator.AccountTier, BigDecimal> invested = NpsCalculator.invested(data.entries());

        List<NpsSummaryResponse.Holding> holdings = new ArrayList<>();
        Map<NpsCalculator.AccountTier, List<NpsSummaryResponse.SchemeUnits>> byTier = new LinkedHashMap<>();
        Map<NpsCalculator.AccountTier, BigDecimal> tierValue = new HashMap<>();
        LocalDate navAsOf = null;
        boolean hasEstimated = false;
        for (NpsCalculator.Holding h : held) {
            NpsScheme scheme = catalog.schemes().get(h.schemeCode());
            InstrumentPrice price = catalog.price(h.schemeCode());
            BigDecimal nav = price == null ? null : price.getClose();
            LocalDate navDate = price == null ? null : price.getAsOf();
            BigDecimal value = valueOf(h.units(), nav);
            String name = catalog.name(h.schemeCode());
            holdings.add(new NpsSummaryResponse.Holding(h.accountId(), h.tier(), h.schemeCode(), name,
                    scheme == null ? null : scheme.getAssetClass(), h.units(), nav, navDate, value, h.estimated()));
            NpsCalculator.AccountTier key = new NpsCalculator.AccountTier(h.accountId(), h.tier());
            byTier.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new NpsSummaryResponse.SchemeUnits(h.schemeCode(), name, h.units(), nav, value));
            tierValue.merge(key, value, BigDecimal::add);
            if (navDate != null && (navAsOf == null || navDate.isAfter(navAsOf))) {
                navAsOf = navDate;
            }
            hasEstimated |= h.estimated();
        }

        List<NpsSummaryResponse.Account> accounts = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        for (NpsAccount account : data.accounts()) {
            List<NpsSummaryResponse.Tier> tiers = new ArrayList<>();
            for (NpsTier tier : NpsTier.values()) {
                NpsCalculator.AccountTier key = new NpsCalculator.AccountTier(account.getId(), tier);
                boolean present = byTier.containsKey(key) || invested.containsKey(key);
                if (!present && !(tier == NpsTier.I || account.isTier2Active())) {
                    continue;
                }
                BigDecimal value = tierValue.getOrDefault(key, BigDecimal.ZERO);
                BigDecimal in = invested.getOrDefault(key, BigDecimal.ZERO);
                tiers.add(new NpsSummaryResponse.Tier(tier, value, in, value.subtract(in),
                        byTier.getOrDefault(key, List.of())));
                totalValue = totalValue.add(value);
                totalInvested = totalInvested.add(in);
            }
            accounts.add(accountResponse(account, tiers, statementEnd.get(account.getId())));
        }

        BigDecimal gain = totalValue.subtract(totalInvested);
        BigDecimal gainPct = totalInvested.signum() > 0
                ? gain.multiply(HUNDRED).divide(totalInvested, 2, RoundingMode.HALF_UP) : null;
        Map<NpsTier, NpsCalculator.Split> fy = NpsCalculator.contributedIn(data.entries(),
                FinancialYear.range(FinancialYear.startYear(today)));
        BigDecimal fySelf = BigDecimal.ZERO;
        BigDecimal fyEmployer = BigDecimal.ZERO;
        List<NpsSummaryResponse.TierSplit> fyByTier = new ArrayList<>();
        for (NpsTier tier : NpsTier.values()) {
            NpsCalculator.Split split = fy.getOrDefault(tier, NpsCalculator.Split.ZERO);
            fyByTier.add(new NpsSummaryResponse.TierSplit(tier, split.self(), split.employer()));
            fySelf = fySelf.add(split.self());
            fyEmployer = fyEmployer.add(split.employer());
        }
        List<NpsSummaryResponse.Allocation> allocationRows = allocations.stream()
                .sorted(Comparator.comparing((NpsAllocation a) -> a.getAccount().getId().toString())
                        .thenComparing(NpsAllocation::getTier).thenComparing(a -> a.getPercent().negate()))
                .map(a -> new NpsSummaryResponse.Allocation(a.getAccount().getId(), a.getTier(), a.getSchemeCode(),
                        a.getPercent(), a.getAsOf()))
                .toList();
        NpsCalculator.Split investedSplit = NpsCalculator.investedSplit(data.entries());
        BigDecimal dayChange = dayChange(held, catalog, totalValue);
        BigDecimal dayChangePct = null;
        if (dayChange != null) {
            BigDecimal previous = totalValue.subtract(dayChange);
            dayChangePct = previous.signum() > 0
                    ? dayChange.multiply(HUNDRED).divide(previous, 2, RoundingMode.HALF_UP) : null;
        }
        boolean stale = navAsOf != null && navAsOf.isBefore(today.minusDays(NAV_STALE_DAYS));
        return new NpsSummaryResponse(
                new NpsSummaryResponse.Hero(totalValue, totalInvested, gain, gainPct, fySelf.add(fyEmployer),
                        NpsCalculator.xirrPercent(data.entries(), totalValue, today), navAsOf,
                        dayChange, dayChangePct, investedSplit.self(), investedSplit.employer()),
                accounts, holdings, new NpsSummaryResponse.ThisFy(fySelf, fyEmployer, fyByTier), allocationRows,
                new NpsSummaryResponse.Flags(hasEstimated, stale, unitsMismatch(statements),
                        statementStale(data, statementEnd, today)),
                gmailDetections.forUser(data.userId()).stream()
                        .map(d -> new NpsSummaryResponse.PendingGmail(d.key(), d.pranLast4(), d.holderName(),
                                d.statementCount(), d.firstPeriodStart(), d.lastPeriodEnd(), d.sectorGuess()))
                        .toList(),
                gmailDetections.lockedCount(data.userId()), gmailDetections.failedCount(data.userId()));
    }

    /** Whether the latest complete statement of any account (and tier) printed units the ledger does not match. */
    private static boolean unitsMismatch(List<NpsStatement> statements) {
        Map<String, NpsStatement> latest = new HashMap<>();
        for (NpsStatement s : statements) {
            if (s.isIncomplete()) {
                continue;
            }
            latest.merge(s.getAccount().getId() + "|" + s.getTier(), s, (a, b) ->
                    Comparator.comparing(NpsStatement::getPeriodEnd).thenComparing(NpsStatement::getPeriodStart)
                            .compare(a, b) >= 0 ? a : b);
        }
        return latest.values().stream().anyMatch(NpsStatement::isUnitsMismatch);
    }

    /** Per account, the newest period end of its complete statements (an account with none is absent). */
    private static Map<UUID, LocalDate> latestStatementEnds(List<NpsStatement> statements) {
        Map<UUID, LocalDate> out = new HashMap<>();
        for (NpsStatement s : statements) {
            if (!s.isIncomplete()) {
                out.merge(s.getAccount().getId(), s.getPeriodEnd(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return out;
    }

    /**
     * True when, among the accounts that have entries and a complete statement, the newest statement end is more
     * than {@value #STATEMENT_STALE_DAYS} days old; false when no account qualifies.
     */
    private static boolean statementStale(Data data, Map<UUID, LocalDate> statementEnd, LocalDate today) {
        Set<UUID> withEntries = data.entries().stream().map(e -> e.getAccount().getId()).collect(Collectors.toSet());
        LocalDate newest = null;
        for (Map.Entry<UUID, LocalDate> e : statementEnd.entrySet()) {
            if (withEntries.contains(e.getKey()) && (newest == null || e.getValue().isAfter(newest))) {
                newest = e.getValue();
            }
        }
        return newest != null && newest.isBefore(today.minusDays(STATEMENT_STALE_DAYS));
    }

    /**
     * The value less the held units at each scheme's previous NAV (the second-latest feed row), from one batch
     * query; null with no holdings or when a held scheme lacks a NAV or a previous one.
     */
    private BigDecimal dayChange(List<NpsCalculator.Holding> held, Catalog catalog, BigDecimal totalValue) {
        if (held.isEmpty()) {
            return null;
        }
        List<String> instrumentIds = new ArrayList<>();
        for (NpsCalculator.Holding h : held) {
            NpsScheme scheme = catalog.schemes().get(h.schemeCode());
            if (scheme == null || catalog.price(h.schemeCode()) == null) {
                return null;
            }
            instrumentIds.add(scheme.getInstrumentId().toString());
        }
        Map<UUID, BigDecimal> previous = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        // Rows come newest first per instrument (feed prices only): the first is the latest, the second the previous.
        for (Object[] row : priceRepository.findLatestTwoCloses(instrumentIds.stream().distinct().toList(), "-")) {
            UUID id = UUID.fromString(row[0].toString());
            if (!seen.add(id)) {
                previous.put(id, row[2] instanceof BigDecimal bd ? bd : new BigDecimal(row[2].toString()));
            }
        }
        BigDecimal previousValue = BigDecimal.ZERO;
        for (NpsCalculator.Holding h : held) {
            BigDecimal prev = previous.get(catalog.schemes().get(h.schemeCode()).getInstrumentId());
            if (prev == null) {
                return null;
            }
            previousValue = previousValue.add(valueOf(h.units(), prev));
        }
        return totalValue.subtract(previousValue);
    }

    private static BigDecimal valueOf(BigDecimal units, BigDecimal nav) {
        return nav == null ? BigDecimal.ZERO : units.multiply(nav).setScale(2, RoundingMode.HALF_UP);
    }

    private static NpsSummaryResponse.Account accountResponse(NpsAccount a, List<NpsSummaryResponse.Tier> tiers,
                                                              LocalDate latestStatementEnd) {
        EpfEmployer employer = a.getEmployer();
        return new NpsSummaryResponse.Account(a.getId(), mask(a.getPranLast4()), a.getHolderName(), a.getRelation(),
                a.getSector(), a.getCra(),
                employer == null ? null : new NpsSummaryResponse.EmployerRef(employer.getId(), employer.getName()),
                a.isTier2Active(), a.getSchemeChoice(), a.getStatus(), a.isReadGmail(), tiers, latestStatementEnd,
                a.getTier1Status(), a.getTier1StatusAsOf());
    }

    static String mask(String last4) {
        return "XXXX XXXX " + last4;
    }

    /** The value of the user's own (not child) NPS holdings, or null with no such account: the net worth row. */
    @Transactional(readOnly = true)
    public BigDecimal getSelfValueOrNull() {
        Data data = load();
        Set<UUID> self = data.accounts().stream().filter(a -> a.getRelation() == NpsRelation.SELF)
                .map(NpsAccount::getId).collect(Collectors.toSet());
        if (self.isEmpty()) {
            return null;
        }
        List<NpsCalculator.Holding> held = NpsCalculator.holdings(data.entries().stream()
                .filter(e -> self.contains(e.getAccount().getId())).toList());
        Catalog catalog = catalog(held.stream().map(NpsCalculator.Holding::schemeCode).collect(Collectors.toSet()));
        BigDecimal total = BigDecimal.ZERO;
        for (NpsCalculator.Holding h : held) {
            InstrumentPrice price = catalog.price(h.schemeCode());
            total = total.add(valueOf(h.units(), price == null ? null : price.getClose()));
        }
        return total;
    }

    // ------------------------------------------------------------------ accounts

    public NpsSummaryResponse.Account createAccount(NpsAccountRequest request) {
        UUID userId = currentUserId();
        NpsAccount account = new NpsAccount();
        account.setUser(userRepository.getReferenceById(userId));
        String pran = normalisePran(request.pran());
        if (pran == null) {
            throw new ValidationException("The PRAN is required.");
        }
        assertPranFree(userId, pran, null);
        account.setPran(pran);
        account.setPranLast4(pran.substring(8));
        apply(account, request, true);
        NpsAccount saved = accountRepository.saveAndFlush(account);
        // Gmail statements held for this PRAN (or ignored before) import now that the account exists
        heldQueue.queueImport(userId, saved);
        return accountResponse(saved.getId());
    }

    /** The account a detected PRAN became, and how many held statements are being imported into it. */
    public record Created(UUID accountId, int importing) {
    }

    /**
     * The user's go-ahead on a PRAN Gmail found: creates the account with this relation and sector (null = not set
     * yet) and queues the import of every held statement of the PRAN.
     */
    public Created createDetectedAccount(String pran, String holderName, NpsRelation relation, NpsSector sector) {
        UUID userId = currentUserId();
        assertPranFree(userId, pran, null);
        NpsAccount account = new NpsAccount();
        account.setUser(userRepository.getReferenceById(userId));
        account.setPran(pran);
        account.setPranLast4(pran.substring(8));
        String holder = holderName == null || holderName.isBlank() ? "NPS account" : holderName.trim();
        account.setHolderName(holder.length() > 200 ? holder.substring(0, 200) : holder);
        account.setRelation(relation);
        account.setSector(sector);
        account.setCra(NpsCra.PROTEAN);
        NpsAccount saved = accountRepository.saveAndFlush(account);
        return new Created(saved.getId(), heldQueue.queueImport(userId, saved));
    }

    public NpsSummaryResponse.Account updateAccount(UUID id, NpsAccountRequest request) {
        NpsAccount account = ownedAccount(id);
        String pran = normalisePran(request.pran());
        boolean changed = false;
        if (pran != null) {
            assertPranFree(currentUserId(), pran, id);
            changed = !pran.equals(account.getPran());
            account.setPran(pran);
            account.setPranLast4(pran.substring(8));
        }
        apply(account, request, false);
        NpsAccount saved = accountRepository.saveAndFlush(account);
        if (changed) {
            // Gmail statements held (or ignored) for the new PRAN import now that an account has it
            heldQueue.queueImport(currentUserId(), saved);
        }
        return accountResponse(id);
    }

    public void deleteAccount(UUID id) {
        NpsAccount account = ownedAccount(id);
        entryRepository.deleteAll(entryRepository.findAllForUser(currentUserId()).stream()
                .filter(e -> e.getAccount().getId().equals(id)).toList());
        allocationRepository.deleteAll(allocationRepository.findForAccounts(List.of(id)));
        statementRepository.deleteAll(statementRepository.findAllForAccount(currentUserId(), id));
        accountRepository.delete(account);
    }

    private NpsSummaryResponse.Account accountResponse(UUID id) {
        entryRepository.flush();
        return buildSummary(load()).accounts().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private void apply(NpsAccount account, NpsAccountRequest request, boolean create) {
        if (request.holderName() != null || create) {
            String name = request.holderName() == null ? "" : request.holderName().trim();
            if (name.isEmpty()) {
                throw new ValidationException("The holder name is required.");
            }
            if (name.length() > 200) {
                throw new ValidationException("The holder name is too long.");
            }
            account.setHolderName(name);
        }
        if (request.relation() != null) {
            account.setRelation(request.relation());
        }
        if (request.sector() != null) {
            account.setSector(request.sector());
        }
        if (request.cra() != null) {
            account.setCra(request.cra());
        }
        if (request.employerId() != null) {
            account.setEmployer(employerRepository.findByIdAndUser_Id(request.employerId(), currentUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("EPF employer", request.employerId())));
        }
        if (request.tier2Active() != null) {
            account.setTier2Active(request.tier2Active());
        }
        if (request.schemeChoice() != null) {
            account.setSchemeChoice(limited(request.schemeChoice(), 80, "The scheme choice"));
        }
        if (request.status() != null) {
            account.setStatus(request.status());
        }
        if (request.readGmail() != null) {
            account.setReadGmail(request.readGmail());
        }
        if (request.statementPassword() != null) {
            String pw = request.statementPassword();
            account.setStatementPassword(pw.isBlank() ? null : pw);
        }
    }

    private static String limited(String value, int max, String label) {
        String v = value.trim();
        if (v.length() > max) {
            throw new ValidationException(label + " is too long.");
        }
        return v.isEmpty() ? null : v;
    }

    /** The 12 digits of a PRAN (spaces and dashes dropped), or null when none was sent; 400 when malformed. */
    static String normalisePran(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[\\s-]", "");
        if (!PRAN.matcher(digits).matches()) {
            throw new ValidationException("A PRAN is 12 digits.");
        }
        return digits;
    }

    private void assertPranFree(UUID userId, String pran, UUID exceptId) {
        for (NpsAccount other : accountRepository.findAllByUser_Id(userId)) {
            if (!other.getId().equals(exceptId) && pran.equals(other.getPran())) {
                throw new DuplicateResourceException("This PRAN is already added.");
            }
        }
    }

    // ------------------------------------------------------------------ allocation

    public NpsSummaryResponse.Account replaceAllocation(UUID accountId, NpsAllocationRequest request) {
        NpsAccount account = ownedAccount(accountId);
        if (request.tier() == null) {
            throw new ValidationException("The tier is required.");
        }
        List<NpsAllocationRequest.Row> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new ValidationException("Add at least one scheme.");
        }
        Set<String> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (NpsAllocationRequest.Row row : rows) {
            if (row.schemeCode() == null || !seen.add(row.schemeCode())) {
                throw new ValidationException("Each scheme can appear once.");
            }
            if (row.percent() == null || row.percent().signum() <= 0 || row.percent().compareTo(HUNDRED) > 0) {
                throw new ValidationException("Each percent must be above 0 and at most 100.");
            }
            sum = sum.add(row.percent());
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw new ValidationException("The percents must add up to 100.");
        }
        requireSchemes(seen);
        allocationRepository.deleteForAccountAndTier(accountId, request.tier());
        LocalDate today = AppTime.today();
        for (NpsAllocationRequest.Row row : rows) {
            NpsAllocation a = new NpsAllocation();
            a.setAccount(account);
            a.setTier(request.tier());
            a.setSchemeCode(row.schemeCode());
            a.setPercent(row.percent().setScale(2, RoundingMode.HALF_UP));
            a.setAsOf(today);
            a.setSource(NpsAllocationSource.MANUAL);
            allocationRepository.save(a);
        }
        account.setSchemeChoice(request.choice() == null ? account.getSchemeChoice()
                : limited(request.choice(), 80, "The choice"));
        accountRepository.saveAndFlush(account);
        return accountResponse(accountId);
    }

    private Map<String, NpsScheme> requireSchemes(Collection<String> codes) {
        Map<String, NpsScheme> found = new HashMap<>();
        schemeRepository.findAllById(new LinkedHashSet<>(codes)).forEach(s -> found.put(s.getSchemeCode(), s));
        for (String code : codes) {
            if (!found.containsKey(code)) {
                throw new ValidationException("Unknown NPS scheme " + code + ".");
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ entries

    @Transactional(readOnly = true)
    public Page<NpsEntryResponse> getEntries(UUID accountId, NpsTier tier, NpsEntryType type, String schemeCode,
                                             LocalDate from, LocalDate to, NpsContributor contributor,
                                             Pageable pageable) {
        UUID userId = currentUserId();
        // The query orders itself (newest first); a client sort must not be appended to it.
        Pageable unsorted = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<UUID> ids = entryRepository.pageIds(userId, accountId, tier, type,
                schemeCode == null || schemeCode.isBlank() ? null : schemeCode.trim(), from, to, contributor, unsorted);
        return new PageImpl<>(entryResponses(ids.getContent()), unsorted, ids.getTotalElements());
    }

    private List<NpsEntryResponse> entryResponses(List<UUID> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, NpsEntry> byId = new HashMap<>();
        entryRepository.findWithLinesByIdIn(orderedIds).forEach(e -> byId.put(e.getId(), e));
        List<NpsEntry> ordered = orderedIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return responses(ordered);
    }

    private List<NpsEntryResponse> responses(List<NpsEntry> entries) {
        Set<String> codes = new LinkedHashSet<>();
        entries.forEach(e -> e.getLines().forEach(l -> codes.add(l.getSchemeCode())));
        Catalog catalog = catalog(codes);
        return entries.stream().map(e -> toResponse(e, catalog)).toList();
    }

    private static NpsEntryResponse toResponse(NpsEntry e, Catalog catalog) {
        List<NpsEntryResponse.Line> lines = e.getLines().stream()
                .sorted(Comparator.comparing(NpsEntryLine::getSchemeCode))
                .map(l -> new NpsEntryResponse.Line(l.getSchemeCode(), catalog.name(l.getSchemeCode()), l.getUnits(),
                        l.getNav(), l.getAmount()))
                .toList();
        return new NpsEntryResponse(e.getId(), e.getAccount().getId(), e.getTier(), e.getType(), e.getContributor(),
                e.getAmount(), e.getPostedDate(), e.getNavDate(), e.getSource(), e.getStatus(), e.getDescription(),
                e.getNote(), e.getTransaction() == null ? null : e.getTransaction().getId(), lines);
    }

    @Transactional(readOnly = true)
    public NpsEntryResponse getEntry(UUID id) {
        return responses(List.of(ownedEntry(id))).get(0);
    }

    public NpsEntryResponse createEntry(NpsEntryRequest request) {
        NpsAccount account = ownedAccount(request.accountId());
        NpsEntry entry = new NpsEntry();
        entry.setUser(account.getUser());
        entry.setAccount(account);
        entry.setSource(NpsEntrySource.MANUAL);
        applyEntry(entry, account, request);
        NpsEntry saved = entryRepository.saveAndFlush(entry);
        reconcile(account.getId(), saved.getTier());
        publishHistoryBackfill(saved);
        return responses(List.of(saved)).get(0);
    }

    /**
     * Redoes the opening reconciliation and the closing checks of one account + tier after a hand-made change to its
     * entries; skipped when the account has no statement (nothing to reconcile against).
     */
    private void reconcile(UUID accountId, NpsTier tier) {
        UUID userId = currentUserId();
        if (statementRepository.findForAccountTier(userId, accountId, tier).isEmpty()) {
            return;
        }
        statementImportService.rebuildReconciliation(userId, accountId, tier);
        statementImportService.checkClosing(userId, accountId, tier);
    }

    /**
     * A contribution recorded from a bank or card payment: SELF, source BANK, ESTIMATED (split by the account's
     * allocation at the NAV on the payment date), linked to the payment.
     */
    public NpsEntryResponse createPaymentEntry(NpsAccount account, NpsTier tier, BigDecimal amount, LocalDate date,
                                               com.financeos.domain.transaction.Transaction payment) {
        NpsEntry entry = new NpsEntry();
        entry.setUser(account.getUser());
        entry.setAccount(account);
        entry.setSource(NpsEntrySource.BANK);
        entry.setTier(tier);
        entry.setType(NpsEntryType.CONTRIBUTION);
        entry.setContributor(NpsContributor.SELF);
        entry.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        entry.setPostedDate(date);
        entry.setTransaction(payment);
        estimateContribution(entry, account, entry.getAmount());
        NpsEntry saved = entryRepository.saveAndFlush(entry);
        publishHistoryBackfill(saved);
        return responses(List.of(saved)).get(0);
    }

    public NpsEntryResponse updateEntry(UUID id, NpsEntryRequest request) {
        NpsEntry entry = ownedEntry(id);
        if (entry.getSource() != NpsEntrySource.MANUAL) {
            throw new ValidationException("Only entries you added by hand can be edited.");
        }
        NpsAccount account = ownedAccount(request.accountId() != null ? request.accountId() : entry.getAccount().getId());
        UUID oldAccountId = entry.getAccount().getId();
        NpsTier oldTier = entry.getTier();
        entry.setAccount(account);
        entry.getLines().clear();
        entryRepository.flush();
        applyEntry(entry, account, request);
        NpsEntry saved = entryRepository.saveAndFlush(entry);
        reconcile(account.getId(), saved.getTier());
        if (!oldAccountId.equals(account.getId()) || oldTier != saved.getTier()) {
            reconcile(oldAccountId, oldTier);
        }
        publishHistoryBackfill(saved);
        return responses(List.of(saved)).get(0);
    }

    public void deleteEntry(UUID id) {
        NpsEntry entry = ownedEntry(id);
        UUID accountId = entry.getAccount().getId();
        NpsTier tier = entry.getTier();
        entryRepository.delete(entry);
        entryRepository.flush();
        reconcile(accountId, tier);
    }

    private NpsEntry ownedEntry(UUID id) {
        return entryRepository.findByIdAndUserId(id, currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("NPS entry", id));
    }

    private void applyEntry(NpsEntry entry, NpsAccount account, NpsEntryRequest request) {
        if (request.type() == null) {
            throw new ValidationException("The entry type is required.");
        }
        if (request.tier() == null) {
            throw new ValidationException("The tier is required.");
        }
        if (request.postedDate() == null) {
            throw new ValidationException("The date is required.");
        }
        BigDecimal amount = request.amount() == null ? null : request.amount().setScale(2, RoundingMode.HALF_UP);
        if (request.type() == NpsEntryType.CONTRIBUTION) {
            if (amount == null || amount.signum() <= 0) {
                throw new ValidationException("A contribution needs an amount above zero.");
            }
        } else if (request.type() == NpsEntryType.WITHDRAWAL && amount != null && amount.signum() > 0) {
            amount = amount.negate();
        }
        entry.setTier(request.tier());
        entry.setType(request.type());
        entry.setContributor(request.type() == NpsEntryType.CONTRIBUTION && request.contributor() == null
                ? NpsContributor.SELF : request.contributor());
        entry.setAmount(amount);
        entry.setPostedDate(request.postedDate());
        entry.setDescription(request.description() == null ? null : limited(request.description(), 300, "The description"));
        entry.setNote(request.note() == null ? null : limited(request.note(), 500, "The note"));

        List<NpsEntryRequest.Line> given = request.lines() == null ? List.of() : request.lines();
        if (!given.isEmpty()) {
            Map<String, NpsScheme> schemes = requireSchemes(given.stream().map(NpsEntryRequest.Line::schemeCode).toList());
            for (NpsEntryRequest.Line l : given) {
                if (l.units() == null || l.units().signum() == 0) {
                    throw new ValidationException("Each line needs units.");
                }
                if (l.nav() != null && l.nav().signum() <= 0) {
                    throw new ValidationException("A NAV must be above zero.");
                }
                BigDecimal units = l.units().setScale(4, RoundingMode.HALF_UP);
                BigDecimal nav = l.nav() == null ? null : l.nav().setScale(4, RoundingMode.HALF_UP);
                entry.addLine(new NpsEntryLine(l.schemeCode(), units, nav,
                        nav == null ? null : units.multiply(nav).setScale(2, RoundingMode.HALF_UP)));
            }
            entry.setStatus(NpsEntryStatus.CONFIRMED);
            entry.setNavDate(request.navDate());
        } else if (request.type() == NpsEntryType.CONTRIBUTION
                || (request.type() == NpsEntryType.OPENING && amount != null && amount.signum() > 0)) {
            estimateContribution(entry, account, amount);
        } else if (request.type() != NpsEntryType.OTHER) {
            throw new ValidationException("Add the units of at least one scheme.");
        } else {
            entry.setStatus(NpsEntryStatus.CONFIRMED);
            entry.setNavDate(request.navDate());
        }
    }

    /**
     * No lines sent for a contribution or an opening balance: the amount splits by the account's allocation for the tier (the last
     * scheme takes the rounding remainder) and each part buys units at the nearest earlier NAV (the nearest later one while the scheme's history isn't loaded;
     * the backfill then re-prices it, see {@link NpsRepriceService}); ESTIMATED until a
     * statement confirms the real units.
     */
    private void estimateContribution(NpsEntry entry, NpsAccount account, BigDecimal amount) {
        List<NpsAllocation> rows = allocationRepository.findForAccounts(List.of(account.getId())).stream()
                .filter(a -> a.getTier() == entry.getTier())
                .sorted(Comparator.comparing(NpsAllocation::getSchemeCode)).toList();
        if (rows.isEmpty()) {
            throw new ValidationException("Set this account's scheme allocation for the tier, or add the units by hand.");
        }
        Map<String, NpsScheme> schemes = requireSchemes(rows.stream().map(NpsAllocation::getSchemeCode).toList());
        BigDecimal left = amount;
        LocalDate navDate = null;
        for (int i = 0; i < rows.size(); i++) {
            NpsAllocation row = rows.get(i);
            BigDecimal part = i == rows.size() - 1 ? left
                    : amount.multiply(row.getPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            left = left.subtract(part);
            if (part.signum() == 0) {
                continue;
            }
            InstrumentPrice price = priceRepository
                    .findFirstByInstrumentIdAndUserIdIsNullAndAsOfLessThanEqualOrderByAsOfDesc(
                            schemes.get(row.getSchemeCode()).getInstrumentId(), entry.getPostedDate())
                    .or(() -> priceRepository
                            .findFirstByInstrumentIdAndUserIdIsNullAndAsOfGreaterThanEqualOrderByAsOfAsc(
                                    schemes.get(row.getSchemeCode()).getInstrumentId(), entry.getPostedDate()))
                    .orElseThrow(() -> new ValidationException("No NAV is available yet for scheme "
                            + row.getSchemeCode() + " on " + entry.getPostedDate() + "."));
            BigDecimal nav = price.getClose().setScale(4, RoundingMode.HALF_UP);
            entry.addLine(new NpsEntryLine(row.getSchemeCode(), part.divide(nav, 4, RoundingMode.HALF_UP), nav, part));
            if (navDate == null || price.getAsOf().isAfter(navDate)) {
                navDate = price.getAsOf();
            }
        }
        entry.setStatus(NpsEntryStatus.ESTIMATED);
        entry.setNavDate(navDate);
    }

    /** A scheme with no NAV on or before the entry's date has no history yet: ask for its backfill once the entry commits. */
    private void publishHistoryBackfill(NpsEntry entry) {
        Set<String> codes = entry.getLines().stream().map(NpsEntryLine::getSchemeCode).collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return;
        }
        Map<String, LocalDate> coverFrom = new HashMap<>();
        codes.forEach(code -> coverFrom.put(code, entry.getPostedDate()));
        historyBackfill.requestFor(coverFrom);
    }

    // ------------------------------------------------------------------ schemes

    @Transactional(readOnly = true)
    public List<NpsSchemeResponse> searchSchemes(String query, NpsSchemeTier tier, NpsVariant variant, String pfm) {
        String search = query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT);
        String pfmCode = pfm == null || pfm.isBlank() ? null : pfm.trim().toUpperCase(Locale.ROOT);
        List<NpsScheme> found = schemeRepository.search(search, tier, variant, pfmCode);
        Map<String, NpsScheme> byCode = new LinkedHashMap<>();
        found.forEach(s -> byCode.put(s.getSchemeCode(), s));
        Catalog catalog = catalog(byCode);
        return found.stream().map(s -> schemeResponse(s, catalog)).toList();
    }

    @Transactional(readOnly = true)
    public NpsSchemeDetailResponse getScheme(String code) {
        NpsScheme scheme = schemeRepository.findById(code == null ? "" : code.trim())
                .orElseThrow(() -> new ResourceNotFoundException("NPS scheme", code));
        Data data = load();
        Catalog catalog = catalog(Map.of(scheme.getSchemeCode(), scheme));
        InstrumentPrice price = catalog.price(scheme.getSchemeCode());
        List<NpsSummaryResponse.Holding> holdings = new ArrayList<>();
        for (NpsCalculator.Holding h : NpsCalculator.holdings(data.entries())) {
            if (h.schemeCode().equals(scheme.getSchemeCode())) {
                BigDecimal nav = price == null ? null : price.getClose();
                holdings.add(new NpsSummaryResponse.Holding(h.accountId(), h.tier(), h.schemeCode(),
                        catalog.name(h.schemeCode()), scheme.getAssetClass(), h.units(), nav,
                        price == null ? null : price.getAsOf(), valueOf(h.units(), nav), h.estimated()));
            }
        }
        List<NpsEntry> touching = data.entries().stream()
                .filter(e -> e.getLines().stream().anyMatch(l -> l.getSchemeCode().equals(scheme.getSchemeCode())))
                .sorted(Comparator.comparing(NpsEntry::getPostedDate, Comparator.reverseOrder())
                        .thenComparing(NpsEntry::getId))
                .toList();
        return new NpsSchemeDetailResponse(schemeResponse(scheme, catalog), holdings, responses(touching));
    }

    private static NpsSchemeResponse schemeResponse(NpsScheme s, Catalog catalog) {
        InstrumentPrice price = catalog.price(s.getSchemeCode());
        return new NpsSchemeResponse(s.getSchemeCode(), s.getInstrumentId(), catalog.name(s.getSchemeCode()),
                s.getPfmCode(), s.getPfmName(), s.getAssetClass(), s.getTier(), s.getVariant(),
                price == null ? null : price.getClose(), price == null ? null : price.getAsOf());
    }
}
