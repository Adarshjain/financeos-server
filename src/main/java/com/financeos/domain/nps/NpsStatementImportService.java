package com.financeos.domain.nps;

import com.financeos.api.nps.dto.NpsImportPreviewResponse;
import com.financeos.api.nps.dto.NpsImportAction;
import com.financeos.api.nps.dto.NpsImportResultResponse;
import com.financeos.api.nps.dto.NpsStatementResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentLocalSearch;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.nps.link.NpsLinkDismissal;
import com.financeos.domain.nps.link.NpsLinkDismissalRepository;
import com.financeos.domain.nps.statement.NpsTxnKind;
import com.financeos.domain.nps.statement.ParsedNpsStatement;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Holding;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Pref;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Txn;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a parsed Protean NPS statement to an account's ledger. Per tier: record the statement (once per
 * period), turn its transactions into entries (upgrading an estimated or email / bank contribution instead of
 * duplicating it), rebuild the "statement wins" opening reconciliation of the whole account + tier in period
 * order (so the order statements arrive in does not matter) and check each statement's closing units against the
 * ledger. A preview runs the same code and rolls everything back. Every query names the user.
 */
@Service
@Transactional
public class NpsStatementImportService {

    static final String RECON_PREFIX = "recon|";
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");
    private static final BigDecimal NAV_TOLERANCE = new BigDecimal("0.00005");
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    private static final int UPGRADE_DAYS = 5;
    private static final int NAV_LOOKBACK_DAYS = 7;
    private static final int DEDUPE_DESC_MAX = 100;
    private static final String MISSING_FIGURES = "Protean sent this month without figures";

    /** The result plus the per-row plan a preview shows. */
    public record Outcome(NpsImportResultResponse result, List<NpsImportPreviewResponse.Row> rows) {
    }

    private final NpsAccountRepository accountRepository;
    private final NpsEntryRepository entryRepository;
    private final NpsStatementRepository statementRepository;
    private final NpsAllocationRepository allocationRepository;
    private final NpsSchemeRepository schemeRepository;
    private final NpsSchemeResolver resolver;
    private final InstrumentPriceRepository priceRepository;
    private final UserRepository userRepository;
    private final InstrumentRepository instrumentRepository;
    private final NpsLinkDismissalRepository dismissalRepository;
    private final NpsHistoryBackfill historyBackfill;

    public NpsStatementImportService(NpsAccountRepository accountRepository, NpsEntryRepository entryRepository,
                                     NpsStatementRepository statementRepository,
                                     NpsAllocationRepository allocationRepository, NpsSchemeRepository schemeRepository,
                                     NpsSchemeResolver resolver, InstrumentPriceRepository priceRepository,
                                     UserRepository userRepository, InstrumentRepository instrumentRepository,
                                     NpsLinkDismissalRepository dismissalRepository,
                                     NpsHistoryBackfill historyBackfill) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.statementRepository = statementRepository;
        this.allocationRepository = allocationRepository;
        this.schemeRepository = schemeRepository;
        this.resolver = resolver;
        this.priceRepository = priceRepository;
        this.userRepository = userRepository;
        this.instrumentRepository = instrumentRepository;
        this.dismissalRepository = dismissalRepository;
        this.historyBackfill = historyBackfill;
    }

    // ------------------------------------------------------------------ apply

    public NpsImportResultResponse apply(UUID userId, UUID accountId, ParsedNpsStatement parsed,
                                         NpsStatementSource source, String gmailMessageId, boolean dryRun) {
        return applyWithRows(userId, accountId, parsed, source, gmailMessageId, null, dryRun).result();
    }

    /**
     * {@link #apply} plus the per-row plan. A dry run writes inside the transaction and rolls it back, so the
     * numbers are exactly what the real import gives; with no account (a PRAN not added yet) it does so against
     * a throwaway account.
     */
    public Outcome applyWithRows(UUID userId, UUID accountId, ParsedNpsStatement parsed, NpsStatementSource source,
                                 String gmailMessageId, String fileSha256, boolean dryRun) {
        NpsAccount account;
        if (accountId == null) {
            if (!dryRun) {
                throw new ValidationException("An account is required.");
            }
            account = throwawayAccount(userId, parsed);
        } else {
            account = accountRepository.findByIdAndUser_Id(accountId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("NPS account", accountId));
        }

        Ctx ctx = new Ctx(userId, account, parsed, source, gmailMessageId, fileSha256);
        ctx.codes = resolver.resolve(printedNames(parsed));
        ctx.schemes = new HashMap<>();
        schemeRepository.findAllById(new LinkedHashSet<>(ctx.codes.values()))
                .forEach(s -> ctx.schemes.put(s.getSchemeCode(), s));

        List<NpsTier> tiers = tiersOf(parsed);
        NpsImportResultResponse first = null;
        int added = 0;
        int upgraded = 0;
        int skipped = 0;
        boolean mismatch = false;
        boolean incomplete = false;
        List<NpsImportResultResponse.Adjustment> adjustments = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        List<NpsImportPreviewResponse.Row> rows = new ArrayList<>();
        Set<NpsTier> latestTiers = EnumSet.noneOf(NpsTier.class);
        for (NpsTier tier : tiers) {
            TierResult t = processTier(ctx, tier, tiers.size() == 1 || tier == NpsTier.I);
            if (first == null) {
                first = t.result;
            }
            added += t.result.added();
            upgraded += t.result.upgraded();
            skipped += t.result.skipped();
            mismatch |= t.result.unitsMismatch();
            incomplete |= t.result.incomplete();
            adjustments.addAll(t.result.adjustments());
            warnings.addAll(t.result.warnings());
            rows.addAll(t.rows);
            if (t.latestComplete) {
                latestTiers.add(tier);
            }
        }
        updateAccount(ctx, latestTiers);

        List<String> unmapped = unmappedNames(ctx);
        unmapped.forEach(n -> warnings.add("Scheme not recognised, its lines were skipped: " + n));
        NpsImportResultResponse result = new NpsImportResultResponse(first == null ? null : first.statementId(),
                account.getId(), parsed.periodStart(), parsed.periodEnd(), added, upgraded, skipped, adjustments, unmapped, mismatch,
                incomplete, new ArrayList<>(warnings));
        if (dryRun) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } else {
            requestHistory(ctx.codes.values(), parsed.periodStart());
        }
        return new Outcome(result, rows);
    }

    /**
     * Asks for NAV history of the statement's schemes back to their earliest ledger entry (the statement's start
     * for a scheme with only a holding); the event fires once this transaction commits.
     */
    void requestHistory(java.util.Collection<String> schemeCodes, LocalDate periodStart) {
        Set<String> codes = new LinkedHashSet<>(schemeCodes);
        if (codes.isEmpty()) {
            return;
        }
        Map<String, LocalDate> coverFrom = new HashMap<>();
        codes.forEach(code -> coverFrom.put(code, periodStart));
        for (Object[] row : entryRepository.findEarliestPostedDates(codes)) {
            LocalDate earliest = (LocalDate) row[1];
            coverFrom.merge((String) row[0], earliest, (a, b) -> b.isBefore(a) ? b : a);
        }
        historyBackfill.requestFor(coverFrom);
    }

    /** What one apply works with. */
    private static final class Ctx {
        final UUID userId;
        final NpsAccount account;
        final ParsedNpsStatement parsed;
        final NpsStatementSource source;
        final String gmailMessageId;
        final String fileSha256;
        Map<String, String> codes = Map.of();
        Map<String, NpsScheme> schemes = Map.of();

        Ctx(UUID userId, NpsAccount account, ParsedNpsStatement parsed, NpsStatementSource source,
            String gmailMessageId, String fileSha256) {
            this.userId = userId;
            this.account = account;
            this.parsed = parsed;
            this.source = source;
            this.gmailMessageId = gmailMessageId;
            this.fileSha256 = fileSha256;
        }
    }

    private record TierResult(NpsImportResultResponse result, List<NpsImportPreviewResponse.Row> rows,
                              boolean latestComplete) {
    }

    /** A parsed transaction row and the scheme it maps to (null when unmapped). */
    private record Line(Txn txn, String code) {
    }

    /** The rows of one (date, kind, description) across schemes: one ledger entry. */
    private static final class Group {
        final NpsTier tier;
        final LocalDate date;
        final NpsTxnKind kind;
        final String description;
        final List<Line> lines = new ArrayList<>();
        NpsImportAction action;

        Group(NpsTier tier, LocalDate date, NpsTxnKind kind, String description) {
            this.tier = tier;
            this.date = date;
            this.kind = kind;
            this.description = description;
        }

        List<Line> mapped() {
            return lines.stream().filter(l -> l.code() != null).toList();
        }
    }

    private NpsAccount throwawayAccount(UUID userId, ParsedNpsStatement parsed) {
        NpsAccount a = new NpsAccount();
        a.setUser(userRepository.getReferenceById(userId));
        String pran = parsed.pran() == null ? "000000000000" : parsed.pran();
        a.setPran(pran);
        a.setPranLast4(pran.substring(Math.max(0, pran.length() - 4)));
        a.setHolderName(nameOrDefault(parsed.subscriberName()));
        return accountRepository.saveAndFlush(a);
    }

    private static String nameOrDefault(String name) {
        return name == null || name.isBlank() ? "NPS account" : name.trim();
    }

    // ------------------------------------------------------------------ per tier

    private TierResult processTier(Ctx c, NpsTier tier, boolean withSummary) {
        ParsedNpsStatement p = c.parsed;
        List<Holding> holdings = p.holdings().stream().filter(h -> tierOf(h.schemeName()) == tier).toList();
        List<Txn> txns = p.txns().stream().filter(t -> tierOf(t.schemeName()) == tier).toList();
        boolean incomplete = isIncomplete(holdings, txns);
        List<Group> groups = group(c, tier, txns);

        List<NpsStatement> statements = statementRepository.findForAccountTier(c.userId, c.account.getId(), tier);
        statements = new ArrayList<>(statements);
        for (NpsStatement s : new ArrayList<>(statements)) {
            if (s.getPeriodStart().equals(p.periodStart()) && s.getPeriodEnd().equals(p.periodEnd())) {
                if (s.isIncomplete() && !incomplete) {
                    // a complete statement of the same period replaces the one Protean sent without figures
                    statementRepository.delete(s);
                    statementRepository.flush();
                    statements.remove(s);
                    continue;
                }
                return alreadyImported(s, groups);
            }
        }

        NpsStatement stmt = statementRepository.saveAndFlush(
                newStatement(c, tier, holdings, txns, incomplete, withSummary));
        List<NpsImportPreviewResponse.Row> rows = new ArrayList<>();
        if (incomplete) {
            for (Group g : groups) {
                g.action = NpsImportAction.SKIP;
                addRows(rows, g);
            }
            return new TierResult(result(stmt, 0, 0, 0, List.of(), c, warningsOf(stmt)), rows, false);
        }

        // the shortest complete statement covering a date supplies its statement entries
        List<NpsStatement> shorter = new ArrayList<>();
        Set<UUID> longerIds = new HashSet<>();
        for (NpsStatement o : statements) {
            if (o.isIncomplete()) {
                continue;
            }
            if (isShorter(o.getPeriodStart(), o.getPeriodEnd(), stmt.getPeriodStart(), stmt.getPeriodEnd())) {
                shorter.add(o);
            } else {
                longerIds.add(o.getId());
            }
        }
        List<NpsEntry> existing = entryRepository.findForAccountTier(c.userId, c.account.getId(), tier);
        List<NpsEntry> superseded = existing.stream()
                .filter(e -> e.getStatementId() != null && longerIds.contains(e.getStatementId()) && !isRecon(e)
                        && !e.getPostedDate().isBefore(stmt.getPeriodStart())
                        && !e.getPostedDate().isAfter(stmt.getPeriodEnd()))
                .toList();
        // a superseded entry that holds a payment link (or a "not this one" dismissal) hands it to its replacement
        Map<UUID, List<Transaction>> dismissedBy = new HashMap<>();
        List<NpsEntry> linked = new ArrayList<>();
        if (!superseded.isEmpty()) {
            List<UUID> ids = superseded.stream().map(NpsEntry::getId).toList();
            for (NpsLinkDismissal d : dismissalRepository.findByEntryIds(ids)) {
                dismissedBy.computeIfAbsent(d.getEntry().getId(), k -> new ArrayList<>()).add(d.getTransaction());
            }
            List<NpsEntry> plain = new ArrayList<>();
            for (NpsEntry e : superseded) {
                if (e.getTransaction() != null || dismissedBy.containsKey(e.getId())) {
                    linked.add(e);
                } else {
                    plain.add(e);
                }
            }
            if (!plain.isEmpty()) {
                entryRepository.deleteAll(plain);
            }
            // freed of the statement and its dedupe key: the replacement may take the key; unmatched ones stay
            for (NpsEntry e : linked) {
                e.setStatementId(null);
                e.setDedupeKey(null);
            }
            entryRepository.saveAll(linked);
            entryRepository.flush();
            Set<UUID> gone = new HashSet<>();
            plain.forEach(e -> gone.add(e.getId()));
            existing = existing.stream().filter(e -> !gone.contains(e.getId())).toList();
        }
        Set<String> keys = new HashSet<>();
        existing.forEach(e -> {
            if (e.getDedupeKey() != null) {
                keys.add(e.getDedupeKey());
            }
        });
        Map<String, List<InstrumentPrice>> prices = feedPrices(c, groups);
        Set<UUID> upgradedIds = new HashSet<>();
        int added = 0;
        int upgraded = 0;
        int skipped = 0;
        List<NpsEntry> created = new ArrayList<>();
        for (Group g : groups) {
            List<Line> mapped = g.mapped();
            if (mapped.isEmpty()) {
                g.action = NpsImportAction.UNMAPPED;
                addRows(rows, g);
                continue;
            }
            if (coveredBy(shorter, g.date)) {
                g.action = NpsImportAction.SKIP;
                skipped++;
                addRows(rows, g);
                continue;
            }
            String key = dedupeKey(tier, g);
            if (keys.contains(key)) {
                g.action = NpsImportAction.SKIP;
                skipped++;
                addRows(rows, g);
                continue;
            }
            BigDecimal total = amountOf(mapped);
            NpsEntry target = isContribution(g.kind) ? upgradeCandidate(existing, upgradedIds, g.date, total, contributorOf(g.kind), stmt, shorter) : null;
            if (target != null) {
                upgradedIds.add(target.getId());
                upgrade(target, c, g, mapped, key, prices);
                g.action = NpsImportAction.UPGRADE;
                upgraded++;
            } else {
                NpsEntry e = newEntry(c, tier, stmt, g, mapped, key, total, prices);
                created.add(e);
                g.action = NpsImportAction.ADD;
                added++;
            }
            keys.add(key);
            addRows(rows, g);
        }
        entryRepository.saveAll(created);
        entryRepository.flush();
        handOverLinks(c.userId, linked, dismissedBy, created);

        List<NpsEntry> recon = rebuildReconciliation(c.userId, c.account.getId(), tier);
        List<NpsStatement> all = checkClosing(c.userId, c.account.getId(), tier);
        NpsStatement mine = all.stream().filter(s -> s.getId().equals(stmt.getId())).findFirst().orElse(stmt);
        List<NpsImportResultResponse.Adjustment> adjustments = new ArrayList<>();
        List<NpsEntryLine> adjustLines = new ArrayList<>();
        for (NpsEntry r : recon) {
            if (stmt.getId().equals(r.getStatementId())) {
                r.getLines().stream().sorted(Comparator.comparing(NpsEntryLine::getSchemeCode)).forEach(adjustLines::add);
            }
        }
        Map<String, String> names = schemeNames(adjustLines.stream().map(NpsEntryLine::getSchemeCode).toList());
        adjustLines.forEach(l -> adjustments.add(new NpsImportResultResponse.Adjustment(l.getSchemeCode(),
                names.get(l.getSchemeCode()), l.getUnits())));
        boolean latest = isLatest(all, mine);
        return new TierResult(result(mine, added, upgraded, skipped, adjustments, c, warningsOf(mine)), rows, latest);
    }

    /**
     * Moves the payment link and entry-level dismissals of superseded entries to the replacement of the same type and
     * contributor (amount within 0.01, date within 5 days, closest date, each replacement once). An entry without a
     * replacement stays as it is.
     */
    private void handOverLinks(UUID userId, List<NpsEntry> linked, Map<UUID, List<Transaction>> dismissedBy,
                               List<NpsEntry> created) {
        if (linked.isEmpty()) {
            return;
        }
        Set<UUID> taken = new HashSet<>();
        List<NpsEntry> gone = new ArrayList<>();
        List<NpsLinkDismissal> moved = new ArrayList<>();
        for (NpsEntry old : linked.stream().sorted(Comparator.comparing(NpsEntry::getPostedDate)).toList()) {
            NpsEntry best = null;
            long bestDays = Long.MAX_VALUE;
            for (NpsEntry n : created) {
                if (taken.contains(n.getId()) || n.getTransaction() != null || n.getType() != old.getType()
                        || n.getContributor() != old.getContributor() || n.getAmount() == null || old.getAmount() == null
                        || n.getAmount().subtract(old.getAmount()).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
                    continue;
                }
                long days = Math.abs(ChronoUnit.DAYS.between(n.getPostedDate(), old.getPostedDate()));
                if (days <= UPGRADE_DAYS && days < bestDays) {
                    best = n;
                    bestDays = days;
                }
            }
            if (best == null) {
                continue;
            }
            taken.add(best.getId());
            best.setTransaction(old.getTransaction());
            for (Transaction t : dismissedBy.getOrDefault(old.getId(), List.of())) {
                NpsLinkDismissal d = new NpsLinkDismissal();
                d.setUser(userRepository.getReferenceById(userId));
                d.setTransaction(t);
                d.setEntry(best);
                moved.add(d);
            }
            old.setTransaction(null);
            gone.add(old);
        }
        if (gone.isEmpty()) {
            return;
        }
        entryRepository.saveAll(gone);
        entryRepository.saveAll(created);
        entryRepository.flush();
        entryRepository.deleteAll(gone);
        entryRepository.flush();
        dismissalRepository.saveAll(moved);
        dismissalRepository.flush();
    }

    /** Scheme code to the scheme's name, two queries for all of them. */
    private Map<String, String> schemeNames(Collection<String> codes) {
        Map<String, String> out = new HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        List<NpsScheme> schemes = schemeRepository.findAllById(new LinkedHashSet<>(codes));
        Map<UUID, Instrument> instruments = new HashMap<>();
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(schemes.stream().map(NpsScheme::getInstrumentId).toList())) {
            instrumentRepository.findAllById(chunk).forEach(i -> instruments.put(i.getId(), i));
        }
        for (NpsScheme s : schemes) {
            Instrument i = instruments.get(s.getInstrumentId());
            if (i != null) {
                out.put(s.getSchemeCode(), i.getName());
            }
        }
        return out;
    }

    private TierResult alreadyImported(NpsStatement s, List<Group> groups) {
        List<NpsImportPreviewResponse.Row> rows = new ArrayList<>();
        int skipped = 0;
        for (Group g : groups) {
            if (g.mapped().isEmpty()) {
                g.action = NpsImportAction.UNMAPPED;
            } else {
                g.action = NpsImportAction.SKIP;
                skipped++;
            }
            addRows(rows, g);
        }
        List<String> warnings = new ArrayList<>(warningsOf(s));
        warnings.add("Already imported");
        return new TierResult(new NpsImportResultResponse(s.getId(), s.getAccount().getId(), s.getPeriodStart(), s.getPeriodEnd(), 0, 0,
                skipped, List.of(), List.of(), s.isUnitsMismatch(), s.isIncomplete(), warnings), rows, false);
    }

    private NpsImportResultResponse result(NpsStatement s, int added, int upgraded, int skipped,
                                           List<NpsImportResultResponse.Adjustment> adjustments, Ctx c,
                                           List<String> warnings) {
        return new NpsImportResultResponse(s.getId(), s.getAccount().getId(), s.getPeriodStart(), s.getPeriodEnd(),
                added, upgraded, skipped,
                adjustments, List.of(), s.isUnitsMismatch(), s.isIncomplete(), warnings);
    }

    private static List<String> warningsOf(NpsStatement s) {
        return s.getWarnings() == null || s.getWarnings().isBlank() ? List.of()
                : List.of(s.getWarnings().split("\n"));
    }

    /** Holdings empty, any holding without units, or any real transaction without units. */
    static boolean isIncomplete(List<Holding> holdings, List<Txn> txns) {
        if (holdings.isEmpty() || holdings.stream().anyMatch(h -> h.units() == null)) {
            return true;
        }
        return txns.stream().anyMatch(t -> t.kind() != NpsTxnKind.OPENING && t.kind() != NpsTxnKind.CLOSING
                && t.units() == null);
    }

    private NpsStatement newStatement(Ctx c, NpsTier tier, List<Holding> holdings, List<Txn> txns,
                                      boolean incomplete, boolean withSummary) {
        ParsedNpsStatement p = c.parsed;
        NpsStatement s = new NpsStatement();
        s.setUser(userRepository.getReferenceById(c.userId));
        s.setAccount(c.account);
        s.setTier(tier);
        s.setPeriodStart(p.periodStart());
        s.setPeriodEnd(p.periodEnd());
        s.setLayout(p.layout() == null ? null : p.layout().name());
        s.setFileSha256(c.fileSha256);
        s.setSource(c.source);
        s.setGmailMessageId(c.gmailMessageId);
        s.setIncomplete(incomplete);
        ParsedNpsStatement.Summary sum = withSummary ? p.summary() : null;
        if (sum != null) {
            s.setValue(sum.value());
            s.setContributionCount(sum.contributionCount());
            s.setTotalContribution(sum.totalContribution());
            s.setTotalWithdrawal(sum.totalWithdrawal());
            s.setNotionalGain(sum.notionalGain());
            s.setXirr(sum.xirrPercent());
        }
        List<String> warnings = new ArrayList<>(p.warnings());
        if (incomplete) {
            warnings.add(MISSING_FIGURES);
        }
        s.setWarnings(warnings.isEmpty() ? null : String.join("\n", warnings));
        if (!incomplete) {
            for (Txn t : txns) {
                String code = c.codes.get(t.schemeName());
                if (t.kind() == NpsTxnKind.OPENING && code != null && t.units() != null) {
                    s.addHolding(new NpsStatementHolding(NpsHoldingKind.OPENING, code, scale4(t.units()), null, null, null));
                }
            }
            Map<String, NpsStatementHolding> closing = new LinkedHashMap<>();
            for (Txn t : txns) {
                String code = c.codes.get(t.schemeName());
                if (t.kind() == NpsTxnKind.CLOSING && code != null && t.units() != null) {
                    closing.put(code, new NpsStatementHolding(NpsHoldingKind.CLOSING, code, scale4(t.units()), null, null, null));
                }
            }
            for (Holding h : holdings) {
                String code = c.codes.get(h.schemeName());
                if (code != null) {
                    closing.put(code, new NpsStatementHolding(NpsHoldingKind.CLOSING, code, scale4(h.units()),
                            h.nav() == null ? null : h.nav().setScale(4, RoundingMode.HALF_UP), h.navDate(), h.value()));
                }
            }
            closing.values().forEach(s::addHolding);
        }
        return s;
    }

    // ------------------------------------------------------------------ transactions

    private List<Group> group(Ctx c, NpsTier tier, List<Txn> txns) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Txn t : txns) {
            if (t.kind() == NpsTxnKind.OPENING || t.kind() == NpsTxnKind.CLOSING || isZeroLine(t)) {
                continue;
            }
            String d = t.description() == null ? "" : t.description().trim();
            Group g = groups.computeIfAbsent(t.date() + "|" + t.kind() + "|" + d, k -> new Group(tier, t.date(), t.kind(), d));
            g.lines.add(new Line(t, c.codes.get(t.schemeName())));
        }
        return new ArrayList<>(groups.values());
    }

    /** A line Protean prints with no units and no money (a scheme the rebalance has not reached yet). */
    private static boolean isZeroLine(Txn t) {
        return t.units() != null && t.units().signum() == 0 && (t.amount() == null || t.amount().signum() == 0);
    }

    private static boolean isContribution(NpsTxnKind kind) {
        return kind == NpsTxnKind.CONTRIBUTION || kind == NpsTxnKind.EMPLOYER_CONTRIBUTION;
    }

    static NpsEntryType typeOf(NpsTxnKind kind) {
        return switch (kind) {
            case CONTRIBUTION, EMPLOYER_CONTRIBUTION -> NpsEntryType.CONTRIBUTION;
            case CHARGE -> NpsEntryType.CHARGE;
            case REBALANCE -> NpsEntryType.REBALANCE;
            case SWITCH -> NpsEntryType.SWITCH;
            case MIGRATION -> NpsEntryType.MIGRATION;
            case WITHDRAWAL -> NpsEntryType.WITHDRAWAL;
            default -> NpsEntryType.OTHER;
        };
    }

    private static BigDecimal amountOf(List<Line> lines) {
        BigDecimal sum = null;
        for (Line l : lines) {
            if (l.txn().amount() != null) {
                sum = (sum == null ? BigDecimal.ZERO : sum).add(l.txn().amount());
            }
        }
        return sum == null ? null : sum.setScale(2, RoundingMode.HALF_UP);
    }

    private static String dedupeKey(NpsTier tier, Group g) {
        String desc = g.description.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
        if (desc.length() > DEDUPE_DESC_MAX) {
            desc = desc.substring(0, DEDUPE_DESC_MAX);
        }
        return "stmt|" + tier + "|" + g.date + "|" + g.kind + "|" + desc;
    }

    private NpsEntry newEntry(Ctx c, NpsTier tier, NpsStatement stmt, Group g, List<Line> mapped, String key,
                              BigDecimal total, Map<String, List<InstrumentPrice>> prices) {
        NpsEntry e = new NpsEntry();
        e.setUser(userRepository.getReferenceById(c.userId));
        e.setAccount(c.account);
        e.setTier(tier);
        e.setType(typeOf(g.kind));
        e.setContributor(contributorOf(g.kind));
        e.setAmount(total);
        e.setPostedDate(g.date);
        e.setSource(NpsEntrySource.STATEMENT);
        e.setStatus(NpsEntryStatus.CONFIRMED);
        e.setDescription(limited(g.description, 300));
        e.setStatementId(stmt.getId());
        e.setDedupeKey(key);
        fillLines(e, c, g, mapped, prices);
        return e;
    }

    private void upgrade(NpsEntry e, Ctx c, Group g, List<Line> mapped, String key,
                         Map<String, List<InstrumentPrice>> prices) {
        e.getLines().clear();
        entryRepository.saveAndFlush(e);
        e.setSource(NpsEntrySource.STATEMENT);
        e.setStatus(NpsEntryStatus.CONFIRMED);
        e.setPostedDate(g.date);
        e.setContributor(contributorOf(g.kind));
        e.setAmount(amountOf(mapped));
        e.setDedupeKey(key);
        fillLines(e, c, g, mapped, prices);
        entryRepository.saveAndFlush(e);
    }

    private static NpsContributor contributorOf(NpsTxnKind kind) {
        return kind == NpsTxnKind.EMPLOYER_CONTRIBUTION ? NpsContributor.EMPLOYER
                : kind == NpsTxnKind.CONTRIBUTION ? NpsContributor.SELF : null;
    }

    private void fillLines(NpsEntry e, Ctx c, Group g, List<Line> mapped, Map<String, List<InstrumentPrice>> prices) {
        LocalDate navDate = null;
        for (Line l : mapped) {
            Txn t = l.txn();
            BigDecimal nav = t.nav() == null ? null : t.nav().setScale(4, RoundingMode.HALF_UP);
            BigDecimal amount = t.amount() == null ? null : t.amount().setScale(2, RoundingMode.HALF_UP);
            e.addLine(new NpsEntryLine(l.code(), scale4(t.units()), nav, amount));
            LocalDate matched = navDateOf(prices.get(l.code()), g.date, t.nav());
            if (matched != null && (navDate == null || matched.isAfter(navDate))) {
                navDate = matched;
            }
        }
        e.setNavDate(navDate);
    }

    /** The latest feed NAV date in [date - 7, date] whose NAV equals the line's (within 0.00005), or null. */
    private static LocalDate navDateOf(List<InstrumentPrice> prices, LocalDate date, BigDecimal nav) {
        if (prices == null || nav == null) {
            return null;
        }
        LocalDate best = null;
        for (InstrumentPrice p : prices) {
            if (!p.getAsOf().isBefore(date.minusDays(NAV_LOOKBACK_DAYS)) && !p.getAsOf().isAfter(date)
                    && p.getClose().subtract(nav).abs().compareTo(NAV_TOLERANCE) <= 0
                    && (best == null || p.getAsOf().isAfter(best))) {
                best = p.getAsOf();
            }
        }
        return best;
    }

    /** The feed prices of every mapped scheme of the groups around their dates, in one query. */
    private Map<String, List<InstrumentPrice>> feedPrices(Ctx c, List<Group> groups) {
        Map<UUID, String> codeByInstrument = new HashMap<>();
        LocalDate from = null;
        LocalDate to = null;
        for (Group g : groups) {
            for (Line l : g.mapped()) {
                NpsScheme s = c.schemes.get(l.code());
                if (s != null) {
                    codeByInstrument.put(s.getInstrumentId(), l.code());
                }
            }
            if (!g.mapped().isEmpty()) {
                from = from == null || g.date.isBefore(from) ? g.date : from;
                to = to == null || g.date.isAfter(to) ? g.date : to;
            }
        }
        Map<String, List<InstrumentPrice>> out = new HashMap<>();
        if (codeByInstrument.isEmpty()) {
            return out;
        }
        for (List<UUID> chunk : InstrumentLocalSearch.chunks(codeByInstrument.keySet())) {
            for (InstrumentPrice p : priceRepository.findFeedRowsBetween(chunk, from.minusDays(NAV_LOOKBACK_DAYS), to)) {
                out.computeIfAbsent(codeByInstrument.get(p.getInstrument().getId()), k -> new ArrayList<>()).add(p);
            }
        }
        return out;
    }

    /** An existing contribution this statement row confirms: not from a statement (or still an estimate), same amount, within 5 days. */
    private static NpsEntry upgradeCandidate(List<NpsEntry> existing, Set<UUID> used, LocalDate date, BigDecimal total,
                                             NpsContributor contributor, NpsStatement mine, List<NpsStatement> shorter) {
        if (total == null) {
            return null;
        }
        NpsEntry best = null;
        long bestDays = Long.MAX_VALUE;
        for (NpsEntry e : existing) {
            // an entry a longer statement upgraded keeps no statement_id; this one's period now owns it
            boolean upgradedByStatement = e.getSource() == NpsEntrySource.STATEMENT && e.getStatementId() == null
                    && e.getDedupeKey() != null && e.getDedupeKey().startsWith("stmt|")
                    && !e.getPostedDate().isBefore(mine.getPeriodStart()) && !e.getPostedDate().isAfter(mine.getPeriodEnd())
                    && !coveredBy(shorter, e.getPostedDate());
            boolean upgradable = e.getSource() != NpsEntrySource.STATEMENT || upgradedByStatement
                    || e.getStatus() == NpsEntryStatus.ESTIMATED || e.getStatus() == NpsEntryStatus.PENDING;
            NpsContributor have = e.getContributor() == null ? NpsContributor.SELF : e.getContributor();
            if (e.getType() != NpsEntryType.CONTRIBUTION || have != contributor || !upgradable || e.getAmount() == null
                    || used.contains(e.getId())
                    || e.getAmount().subtract(total).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
                continue;
            }
            long days = Math.abs(ChronoUnit.DAYS.between(e.getPostedDate(), date));
            if (days <= UPGRADE_DAYS && (days < bestDays
                    || (days == bestDays && best != null && e.getId().compareTo(best.getId()) < 0))) {
                best = e;
                bestDays = days;
            }
        }
        return best;
    }

    /** Whether period A is shorter than B; equal lengths go to the later start (equal start and length is the same period). */
    private static boolean isShorter(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        long a = ChronoUnit.DAYS.between(aStart, aEnd);
        long b = ChronoUnit.DAYS.between(bStart, bEnd);
        return a < b || (a == b && aStart.isAfter(bStart));
    }

    private static boolean coveredBy(List<NpsStatement> statements, LocalDate date) {
        for (NpsStatement s : statements) {
            if (!date.isBefore(s.getPeriodStart()) && !date.isAfter(s.getPeriodEnd())) {
                return true;
            }
        }
        return false;
    }

    private static void addRows(List<NpsImportPreviewResponse.Row> rows, Group g) {
        for (Line l : g.lines) {
            Txn t = l.txn();
            rows.add(new NpsImportPreviewResponse.Row(g.date, g.tier, typeOf(g.kind), g.description, l.code(),
                    t.schemeName(), t.units(), t.nav(), t.amount(),
                    l.code() == null ? NpsImportAction.UNMAPPED : g.action));
        }
    }

    // ------------------------------------------------------------------ reconciliation

    /**
     * Rebuilds the opening reconciliation of one account + tier from scratch: deletes the "recon|" entries, then
     * walks the complete statements oldest first and, where a statement's opening units differ from what the
     * ledger holds the day before, writes one entry dated the period start that makes them equal. Returns the
     * entries it wrote.
     */
    public List<NpsEntry> rebuildReconciliation(UUID userId, UUID accountId, NpsTier tier) {
        List<NpsEntry> entries = entryRepository.findForAccountTier(userId, accountId, tier);
        List<NpsEntry> recon = entries.stream().filter(NpsStatementImportService::isRecon).toList();
        if (!recon.isEmpty()) {
            entryRepository.deleteAll(recon);
            entryRepository.flush();
        }
        List<NpsEntry> others = entries.stream().filter(e -> !isRecon(e)).toList();
        NpsAccount account = accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("NPS account", accountId));
        Set<UUID> migrationStatements = new HashSet<>();
        for (NpsEntry e : others) {
            if (e.getType() == NpsEntryType.MIGRATION && e.getStatementId() != null) {
                migrationStatements.add(e.getStatementId());
            }
        }

        List<NpsEntry> created = new ArrayList<>();
        Map<String, BigDecimal> reconUnits = new HashMap<>();
        for (NpsStatement s : statementRepository.findForAccountTier(userId, accountId, tier)) {
            if (s.isIncomplete()) {
                continue;
            }
            Map<String, BigDecimal> opening = unitsOf(s, NpsHoldingKind.OPENING);
            if (opening.isEmpty()) {
                continue;
            }
            Map<String, BigDecimal> ledger = new HashMap<>(unitsAsOf(others, s.getPeriodStart().minusDays(1)));
            reconUnits.forEach((k, v) -> ledger.merge(k, v, BigDecimal::add));
            Map<String, BigDecimal> diffs = new LinkedHashMap<>();
            Set<String> codes = new java.util.TreeSet<>(opening.keySet());
            ledger.forEach((k, v) -> {
                if (v.abs().compareTo(TOLERANCE) > 0) {
                    codes.add(k);
                }
            });
            for (String code : codes) {
                BigDecimal diff = opening.getOrDefault(code, BigDecimal.ZERO)
                        .subtract(ledger.getOrDefault(code, BigDecimal.ZERO));
                if (diff.abs().compareTo(TOLERANCE) > 0) {
                    diffs.put(code, scale4(diff));
                }
            }
            if (diffs.isEmpty()) {
                continue;
            }
            boolean ledgerEmpty = ledger.values().stream().allMatch(v -> v.abs().compareTo(TOLERANCE) <= 0)
                    && created.isEmpty();
            NpsEntry e = new NpsEntry();
            e.setUser(account.getUser());
            e.setAccount(account);
            e.setTier(tier);
            e.setType(ledgerEmpty ? NpsEntryType.OPENING
                    : migrationStatements.contains(s.getId()) ? NpsEntryType.MIGRATION : NpsEntryType.ADJUSTMENT);
            e.setAmount(ledgerEmpty ? openingAmount(s, others) : null);
            e.setPostedDate(s.getPeriodStart());
            e.setSource(NpsEntrySource.STATEMENT);
            e.setStatus(NpsEntryStatus.CONFIRMED);
            e.setDescription("Opening balance per statement");
            e.setStatementId(s.getId());
            e.setDedupeKey(RECON_PREFIX + tier + "|" + s.getPeriodStart() + "|" + s.getId());
            diffs.forEach((code, units) -> e.addLine(new NpsEntryLine(code, units, null, null)));
            diffs.forEach((code, units) -> reconUnits.merge(code, units, BigDecimal::add));
            created.add(e);
        }
        entryRepository.saveAll(created);
        entryRepository.flush();
        return created;
    }

    /** What the first statement's opening balance cost: its lifetime contributions less those inside the period. */
    private static BigDecimal openingAmount(NpsStatement s, List<NpsEntry> others) {
        if (s.getTotalContribution() == null) {
            return null;
        }
        BigDecimal inside = BigDecimal.ZERO;
        for (NpsEntry e : others) {
            if (e.getType() == NpsEntryType.CONTRIBUTION && e.getAmount() != null
                    && e.getStatus() != NpsEntryStatus.PENDING
                    && !e.getPostedDate().isBefore(s.getPeriodStart()) && !e.getPostedDate().isAfter(s.getPeriodEnd())) {
                inside = inside.add(e.getAmount());
            }
        }
        return s.getTotalContribution().subtract(inside).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean isRecon(NpsEntry e) {
        return e.getDedupeKey() != null && e.getDedupeKey().startsWith(RECON_PREFIX);
    }

    /**
     * Sets {@code unitsMismatch} on every complete statement of the account + tier: the ledger's units per
     * scheme on the period end against the units the statement printed at its close. Returns all its statements.
     */
    public List<NpsStatement> checkClosing(UUID userId, UUID accountId, NpsTier tier) {
        List<NpsEntry> entries = entryRepository.findForAccountTier(userId, accountId, tier);
        List<NpsStatement> statements = statementRepository.findForAccountTier(userId, accountId, tier);
        for (NpsStatement s : statements) {
            if (s.isIncomplete()) {
                continue;
            }
            Map<String, BigDecimal> closing = unitsOf(s, NpsHoldingKind.CLOSING);
            if (closing.isEmpty()) {
                continue;
            }
            Map<String, BigDecimal> ledger = unitsAsOf(entries, s.getPeriodEnd());
            Set<String> codes = new HashSet<>(closing.keySet());
            codes.addAll(ledger.keySet());
            boolean mismatch = false;
            for (String code : codes) {
                BigDecimal diff = closing.getOrDefault(code, BigDecimal.ZERO)
                        .subtract(ledger.getOrDefault(code, BigDecimal.ZERO));
                if (diff.abs().compareTo(TOLERANCE) > 0) {
                    mismatch = true;
                    break;
                }
            }
            if (mismatch != s.isUnitsMismatch()) {
                s.setUnitsMismatch(mismatch);
                statementRepository.save(s);
            }
        }
        statementRepository.flush();
        return statements;
    }

    private static Map<String, BigDecimal> unitsOf(NpsStatement s, NpsHoldingKind kind) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (NpsStatementHolding h : s.getHoldings()) {
            if (h.getKind() == kind) {
                out.merge(h.getSchemeCode(), h.getUnits(), BigDecimal::add);
            }
        }
        return out;
    }

    /** Units per scheme of the counting (not PENDING) entries posted on or before the date. */
    private static Map<String, BigDecimal> unitsAsOf(Collection<NpsEntry> entries, LocalDate asOf) {
        Map<String, BigDecimal> units = new HashMap<>();
        for (NpsEntry e : entries) {
            if (e.getStatus() == NpsEntryStatus.PENDING || e.getPostedDate().isAfter(asOf)) {
                continue;
            }
            for (NpsEntryLine l : e.getLines()) {
                units.merge(l.getSchemeCode(), l.getUnits(), BigDecimal::add);
            }
        }
        return units;
    }

    /** Whether the statement is the latest complete one (by period end, then start) of its account + tier. */
    private static boolean isLatest(List<NpsStatement> all, NpsStatement s) {
        if (s.isIncomplete()) {
            return false;
        }
        return all.stream().filter(o -> !o.isIncomplete())
                .max(Comparator.comparing(NpsStatement::getPeriodEnd).thenComparing(NpsStatement::getPeriodStart))
                .map(o -> o.getId().equals(s.getId())).orElse(false);
    }

    // ------------------------------------------------------------------ account

    /** The newest complete statement sets the account's scheme choice, allocation and Tier II flag. */
    private void updateAccount(Ctx c, Set<NpsTier> latestTiers) {
        ParsedNpsStatement p = c.parsed;
        NpsAccount account = c.account;
        boolean changed = false;
        if (account.getHolderName() == null || account.getHolderName().isBlank()) {
            account.setHolderName(nameOrDefault(p.subscriberName()));
            changed = true;
        }
        if (!latestTiers.isEmpty()) {
            if (p.schemeChoice() != null && !p.schemeChoice().isBlank()) {
                account.setSchemeChoice(limited(p.schemeChoice(), 80));
                changed = true;
            }
            String tier2 = p.tierStatuses() == null ? null : p.tierStatuses().get("Tier II Status");
            if (tier2 != null) {
                account.setTier2Active("ACTIVE".equalsIgnoreCase(tier2.trim()));
                changed = true;
            }
            for (NpsTier tier : latestTiers) {
                replaceAllocation(c, tier);
            }
        }
        // Tier I status: from any statement, complete or not, but never older than the stored one
        NpsTier1Status status = NpsTier1Status.fromStatement(
                p.tierStatuses() == null ? null : p.tierStatuses().get("Tier I Status"));
        if (status != null && p.periodEnd() != null && (account.getTier1StatusAsOf() == null
                || !p.periodEnd().isBefore(account.getTier1StatusAsOf()))) {
            account.setTier1Status(status);
            account.setTier1StatusAsOf(p.periodEnd());
            changed = true;
        }
        if (changed) {
            accountRepository.saveAndFlush(account);
        }
    }

    private void replaceAllocation(Ctx c, NpsTier tier) {
        List<NpsAllocation> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Pref pref : c.parsed.prefs()) {
            String code = c.codes.get(pref.schemeName());
            if (tierOf(pref.schemeName()) != tier || code == null || pref.percent() == null
                    || pref.percent().signum() <= 0 || !seen.add(code)) {
                continue;
            }
            NpsAllocation a = new NpsAllocation();
            a.setAccount(c.account);
            a.setTier(tier);
            a.setSchemeCode(code);
            a.setPercent(pref.percent().setScale(2, RoundingMode.HALF_UP));
            a.setAsOf(c.parsed.periodEnd());
            a.setSource(NpsAllocationSource.STATEMENT);
            rows.add(a);
        }
        if (rows.isEmpty()) {
            return;
        }
        for (NpsAllocation old : allocationRepository.findForAccounts(List.of(c.account.getId()))) {
            if (old.getTier() == tier && old.getAsOf().isAfter(c.parsed.periodEnd())) {
                return;
            }
        }
        allocationRepository.deleteForAccountAndTier(c.account.getId(), tier);
        allocationRepository.saveAll(rows);
    }

    // ------------------------------------------------------------------ names and tiers

    private static NpsTier tierOf(String schemeName) {
        NpsSchemeTier t = NpsSchemeNameParser.parse(schemeName).tier();
        return t == NpsSchemeTier.II || t == NpsSchemeTier.TTS ? NpsTier.II : NpsTier.I;
    }

    private static List<NpsTier> tiersOf(ParsedNpsStatement p) {
        Set<NpsTier> tiers = new LinkedHashSet<>();
        p.holdings().forEach(h -> tiers.add(tierOf(h.schemeName())));
        p.txns().forEach(t -> tiers.add(tierOf(t.schemeName())));
        if (tiers.isEmpty()) {
            p.prefs().forEach(x -> tiers.add(tierOf(x.schemeName())));
        }
        if (tiers.isEmpty()) {
            tiers.add(NpsTier.I);
        }
        return new ArrayList<>(tiers);
    }

    private static Set<String> printedNames(ParsedNpsStatement p) {
        Set<String> names = new LinkedHashSet<>();
        p.holdings().forEach(h -> names.add(h.schemeName()));
        p.txns().forEach(t -> names.add(t.schemeName()));
        p.prefs().forEach(x -> names.add(x.schemeName()));
        names.removeIf(n -> n == null || n.isBlank());
        return names;
    }

    private static List<String> unmappedNames(Ctx c) {
        List<String> out = new ArrayList<>();
        for (String name : printedNames(c.parsed)) {
            if (!c.codes.containsKey(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    private static String limited(String s, int max) {
        String t = s == null ? null : s.trim();
        return t == null || t.length() <= max ? t : t.substring(0, max);
    }

    // ------------------------------------------------------------------ statements

    @Transactional(readOnly = true)
    public List<NpsStatementResponse> listStatements(UUID userId, UUID accountId) {
        if (accountId != null) {
            accountRepository.findByIdAndUser_Id(accountId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("NPS account", accountId));
        }
        return statementRepository.findForUser(userId, accountId).stream()
                .sorted(Comparator.comparing(NpsStatement::getPeriodStart).reversed()
                        .thenComparing(NpsStatement::getId))
                .map(s -> new NpsStatementResponse(s.getId(), s.getAccount().getId(), s.getTier(), s.getPeriodStart(),
                        s.getPeriodEnd(), s.getSource(), s.getImportedAt(), s.isUnitsMismatch(), s.isIncomplete(),
                        s.warningCount(), warningsOf(s)))
                .toList();
    }

    /**
     * Deletes a statement and the entries it created; an entry it only upgraded keeps its data (its
     * statement_id is null). The account + tier's reconciliation and closing checks are redone.
     */
    public void deleteStatement(UUID userId, UUID statementId) {
        NpsStatement s = statementRepository.findByIdAndUserId(statementId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("NPS statement", statementId));
        UUID accountId = s.getAccount().getId();
        NpsTier tier = s.getTier();
        entryRepository.deleteAll(entryRepository.findByStatementId(userId, statementId));
        statementRepository.delete(s);
        statementRepository.flush();
        rebuildReconciliation(userId, accountId, tier);
        checkClosing(userId, accountId, tier);
    }
}
