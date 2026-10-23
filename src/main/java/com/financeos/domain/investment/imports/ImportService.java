package com.financeos.domain.investment.imports;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.api.investment.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.*;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dividend.DividendType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private final List<ImportParser> parsers;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentAliasRepository aliasRepository;
    private final HoldingRepository holdingRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final InstrumentSearchService instrumentSearchService;
    private final ApplicationEventPublisher eventPublisher;
    /** Where the user's repointed instruments now go; null in unit tests (then nothing is repointed). */
    @org.springframework.lang.Nullable
    private InstrumentRepointMap repointMap;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRepointMap(InstrumentRepointMap repointMap) {
        this.repointMap = repointMap;
    }

    /** {@code instrument} as the signed-in user's imports see it (their repoint applied after any match). */
    private Instrument repointed(Instrument instrument) {
        return repointMap == null || instrument == null ? instrument : repointMap.applyForCurrentUser(instrument);
    }

    public ImportService(List<ImportParser> parsers,
                         InstrumentRepository instrumentRepository,
                         InstrumentAliasRepository aliasRepository,
                         HoldingRepository holdingRepository,
                         InvestmentTransactionRepository transactionRepository,
                         DividendRepository dividendRepository,
                         AccountRepository accountRepository,
                         UserRepository userRepository,
                         InstrumentSearchService instrumentSearchService,
                         ApplicationEventPublisher eventPublisher) {
        this.parsers = parsers;
        this.instrumentRepository = instrumentRepository;
        this.aliasRepository = aliasRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.dividendRepository = dividendRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.instrumentSearchService = instrumentSearchService;
        this.eventPublisher = eventPublisher;
    }

    public ImportPreviewResponse preview(InputStream inputStream, ImportSource source, UUID brokerAccountId) {
        return preview(inputStream, source, brokerAccountId, null);
    }

    public ImportPreviewResponse preview(InputStream inputStream, ImportSource source, UUID brokerAccountId, String password) {
        Account brokerAccount = accountRepository.findByIdAndUser_Id(brokerAccountId, UserContext.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", brokerAccountId));

        if (brokerAccount.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        ImportParser parser = parsers.stream()
                .filter(p -> p.source() == source)
                .findFirst()
                .orElseThrow(() -> new ValidationException("No import parser configured for source " + source));

        List<ParsedRow> parsedRows = parser.parse(inputStream, new ParseContext(brokerAccountId, password));

        List<ImportPreviewResponse.ImportRowPreviewDto> rowDtos = new ArrayList<>();
        // Matched instruments are shown as this user sees them (their own names).
        InstrumentOverrides overrides = instrumentSearchService != null
                ? InstrumentOverrides.orNone(instrumentSearchService.currentUserOverrides()) : InstrumentOverrides.NONE;
        int matchedCount = 0;
        int unmatchedCount = 0;
        int duplicateCount = 0;
        int errorCount = 0;

        // Per-import batches (never one query per row): instruments by ISIN, own and catalog aliases by symbol.
        ImportLookups lookups = new ImportLookups(brokerAccountId);
        lookups.loadIsins(parsedRows.stream().filter(r -> r.error() == null)
                .map(ParsedRow::parsedIsin).filter(i -> i != null && !i.isBlank()).toList());
        lookups.loadAliases(parsedRows.stream().filter(r -> r.error() == null)
                .map(ParsedRow::parsedSymbol).filter(sym -> sym != null && !sym.isBlank()).toList());

        // Pass 1 resolves every row's instrument (creating unmatched ones, so later rows can reuse them);
        // pass 2 checks duplicates against holdings, dividends and transactions loaded once.
        List<ResolvedRow> resolvedRows = new ArrayList<>();
        for (ParsedRow row : parsedRows) {
            if (row.error() != null) {
                resolvedRows.add(new ResolvedRow(row, null));
                continue;
            }

            String resolveSource = "local";
            String resolveOutcome = "unmatched";
            int resolveCandidateCount = 0;

            // Resolve instrument
            Instrument matchedInstrument = null;
            if (row.parsedIsin() != null && !row.parsedIsin().isBlank()) {
                matchedInstrument = lookups.isin(row.parsedIsin());
                if (matchedInstrument != null) {
                    resolveSource = "isin";
                    resolveOutcome = "matched";
                    resolveCandidateCount = 1;
                }
            }

            if (matchedInstrument == null && row.parsedSymbol() != null && !row.parsedSymbol().isBlank()) {
                // The user's own alias (their repointed instrument) beats a catalog symbol match.
                matchedInstrument = lookups.ownAlias(row.parsedSymbol());
                if (matchedInstrument != null) {
                    resolveSource = "name";
                    resolveOutcome = "matched";
                    resolveCandidateCount = 1;
                }
            }
            if (matchedInstrument == null && row.parsedSymbol() != null && !row.parsedSymbol().isBlank()) {
                List<Instrument> searchResults = instrumentRepository.searchInstruments(row.parsedSymbol(), null);
                for (Instrument inst : searchResults) {
                    if (inst.getSymbol() != null && inst.getSymbol().equalsIgnoreCase(row.parsedSymbol())) {
                        if (row.exchange() == null || inst.getExchange() == null || inst.getExchange().equalsIgnoreCase(row.exchange())) {
                            matchedInstrument = inst;
                            resolveSource = "name";
                            resolveOutcome = "matched";
                            resolveCandidateCount = searchResults.size();
                            break;
                        }
                    }
                }
                if (matchedInstrument == null && aliasRepository != null) {
                    matchedInstrument = lookups.catalogAlias(row.parsedSymbol());
                    if (matchedInstrument != null) {
                        resolveSource = "name";
                        resolveOutcome = "matched";
                        resolveCandidateCount = 1;
                    }
                }
            }

            boolean externalSearchExhausted = false;

            // Auto-find via external APIs (Yahoo Finance / AMFI) if not matched locally
            if (matchedInstrument == null && instrumentSearchService != null) {
                try {
                    InstrumentType searchType = (source == ImportSource.mf_cas) ? InstrumentType.mutual_fund : InstrumentType.stock;
                    String isinStr = (row.parsedIsin() != null && !row.parsedIsin().isBlank()) ? row.parsedIsin().trim() : null;
                    String fallbackQueryStr = (searchType == InstrumentType.mutual_fund)
                            ? (row.parsedName() != null && !row.parsedName().isBlank() ? row.parsedName() : row.parsedSymbol())
                            : (row.parsedSymbol() != null && !row.parsedSymbol().isBlank() ? row.parsedSymbol() : row.parsedName());

                    List<InstrumentCandidate> candidates = List.of();
                    if (isinStr != null && isinStr.length() >= 2) {
                        candidates = instrumentSearchService.catalogSearch(isinStr, searchType);
                    }

                    if (candidates.isEmpty() && fallbackQueryStr != null && fallbackQueryStr.trim().length() >= 2) {
                        candidates = instrumentSearchService.catalogSearch(fallbackQueryStr.trim(), searchType);
                    }

                    externalSearchExhausted = candidates.isEmpty();
                    resolveCandidateCount = candidates.size();

                    if (!candidates.isEmpty()) {
                        InstrumentCandidate bestCandidate = null;

                        // 1. Exact ISIN match
                        if (row.parsedIsin() != null && !row.parsedIsin().isBlank()) {
                            for (InstrumentCandidate candidate : candidates) {
                                if (candidate.isin() != null && candidate.isin().equalsIgnoreCase(row.parsedIsin().trim())) {
                                    bestCandidate = candidate;
                                    break;
                                }
                            }
                        }

                        // 2. Exact Symbol & Exchange match
                        if (bestCandidate == null && row.parsedSymbol() != null && !row.parsedSymbol().isBlank()) {
                            for (InstrumentCandidate candidate : candidates) {
                                if (candidate.symbol() != null && candidate.symbol().equalsIgnoreCase(row.parsedSymbol().trim())) {
                                    if (row.exchange() == null || candidate.exchange() == null || candidate.exchange().equalsIgnoreCase(row.exchange())) {
                                        bestCandidate = candidate;
                                        break;
                                    }
                                }
                            }
                        }

                        // 3. Top candidate match
                        if (bestCandidate == null && !candidates.isEmpty()) {
                            InstrumentCandidate top = candidates.get(0);
                            if (top.symbol() != null && row.parsedSymbol() != null && top.symbol().equalsIgnoreCase(row.parsedSymbol().trim())) {
                                bestCandidate = top;
                            } else if (top.name() != null && row.parsedName() != null && top.name().toLowerCase().contains(row.parsedName().toLowerCase())) {
                                bestCandidate = top;
                            } else if (row.parsedSymbol() != null && !row.parsedSymbol().isBlank()) {
                                bestCandidate = top;
                            }
                        }

                        if (bestCandidate != null && bestCandidate.existingInstrumentId() != null) {
                            // A catalog row (shown with the user's own overrides): used as it is, never
                            // re-resolved from those display values.
                            matchedInstrument = instrumentRepository.findById(bestCandidate.existingInstrumentId()).orElse(null);
                            if (matchedInstrument != null) {
                                resolveSource = "search";
                                resolveOutcome = "matched";
                            }
                        } else if (bestCandidate != null) {
                            // TRUSTED may fill a shared row's empty identifiers, so only what the provider
                            // returned goes in — never a value from the user's file (e.g. its ISIN).
                            InstrumentResponse resolved = instrumentSearchService.resolve(new ResolveInstrumentRequest(
                                    bestCandidate.type(),
                                    bestCandidate.name(),
                                    bestCandidate.symbol(),
                                    bestCandidate.exchange(),
                                    bestCandidate.isin(),
                                    bestCandidate.amfiCode(),
                                    bestCandidate.yahooSymbol(),
                                    bestCandidate.currency(),
                                    null
                            ));
                            matchedInstrument = instrumentRepository.findById(resolved.id()).orElse(null);
                            if (matchedInstrument != null) {
                                resolveSource = "search";
                                resolveOutcome = "matched";
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Auto-find API search failed for row {}: {}", row.rowIndex(), e.getMessage());
                }
            }

            // Fallback: If still unmatched, auto-create and map the Instrument directly using details from CSV
            if (matchedInstrument == null) {
                try {
                    Instrument newInst = new Instrument();
                    InstrumentType defaultType = (source == ImportSource.mf_cas || (row.parsedIsin() != null && row.parsedIsin().startsWith("INF")))
                            ? InstrumentType.mutual_fund
                            : InstrumentType.stock;
                    newInst.setType(defaultType);

                    String name = row.parsedName();
                    if (name == null || name.isBlank()) {
                        name = row.parsedSymbol() != null ? row.parsedSymbol() : "Unknown Instrument";
                    }
                    newInst.setName(name.trim());
                    newInst.setSymbol(row.parsedSymbol() != null ? row.parsedSymbol().trim() : null);
                    newInst.setExchange(row.exchange() != null ? row.exchange().trim() : (defaultType == InstrumentType.mutual_fund ? "MUTUAL_FUND" : "NSE"));
                    newInst.setIsin(row.parsedIsin() != null ? row.parsedIsin().trim() : null);

                    boolean likelyDelisted = externalSearchExhausted && row.parsedIsin() != null && !row.parsedIsin().isBlank();

                    String yahooSym = null;
                    if (!likelyDelisted && defaultType == InstrumentType.stock && newInst.getSymbol() != null && !newInst.getSymbol().isBlank()) {
                        String ex = newInst.getExchange();
                        yahooSym = newInst.getSymbol().toUpperCase() + ("BSE".equalsIgnoreCase(ex) ? ".BO" : ".NS");
                    }
                    newInst.setYahooSymbol(yahooSym);

                    matchedInstrument = instrumentRepository.save(newInst);
                    if (matchedInstrument != null) {
                        lookups.created(matchedInstrument);
                        resolveSource = "search";
                        resolveOutcome = "created";
                    }
                } catch (Exception e) {
                    log.warn("Failed to auto-create fallback instrument for row {}: {}", row.rowIndex(), e.getMessage());
                    if (row.parsedSymbol() != null && !row.parsedSymbol().isBlank()) {
                        List<Instrument> searchResults = instrumentRepository.searchInstruments(row.parsedSymbol().trim(), null);
                        InstrumentType defaultType = (source == ImportSource.mf_cas || (row.parsedIsin() != null && row.parsedIsin().startsWith("INF")))
                                ? InstrumentType.mutual_fund
                                : InstrumentType.stock;
                        String rowExchange = row.exchange() != null ? row.exchange().trim() : (defaultType == InstrumentType.mutual_fund ? "MUTUAL_FUND" : "NSE");
                        String rowIsin = row.parsedIsin() != null ? row.parsedIsin().trim() : null;

                        for (Instrument inst : searchResults) {
                            if (inst.getSymbol() != null && inst.getSymbol().equalsIgnoreCase(row.parsedSymbol().trim())) {
                                boolean exMatch = inst.getExchange() == null || rowExchange == null || inst.getExchange().equalsIgnoreCase(rowExchange);
                                if (exMatch) {
                                    String existingIsin = inst.getIsin();
                                    boolean isinCompatible = (rowIsin == null || rowIsin.isBlank() || existingIsin == null || existingIsin.isBlank() || existingIsin.equalsIgnoreCase(rowIsin));
                                    if (isinCompatible) {
                                        matchedInstrument = inst;
                                        resolveSource = "name";
                                        resolveOutcome = "matched";
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (matchedInstrument == null) {
                resolveOutcome = externalSearchExhausted ? "dead" : "ambiguous";
            } else {
                // However it matched (ISIN, AMFI, Yahoo, ticker, alias, search, created), an instrument the
                // user moved off stands for the one they moved to — so duplicates are checked there too.
                matchedInstrument = lookups.repointed(matchedInstrument);
            }

            log.info("Instrument resolve: isin={}, symbol={}, source={}, outcome={}",
                    row.parsedIsin(), row.parsedSymbol(), resolveSource, resolveOutcome,
                    StructuredArguments.keyValue("event", Events.INSTRUMENT_RESOLVE),
                    StructuredArguments.keyValue("isin", row.parsedIsin() != null ? row.parsedIsin() : ""),
                    StructuredArguments.keyValue("symbol", row.parsedSymbol() != null ? row.parsedSymbol() : ""),
                    StructuredArguments.keyValue("source", resolveSource),
                    StructuredArguments.keyValue("outcome", resolveOutcome),
                    StructuredArguments.keyValue("candidateCount", resolveCandidateCount));

            resolvedRows.add(new ResolvedRow(row, matchedInstrument));
        }

        Set<UUID> dividendInstrumentIds = new HashSet<>();
        Set<UUID> tradeInstrumentIds = new HashSet<>();
        for (ResolvedRow resolved : resolvedRows) {
            if (resolved.instrument() == null) {
                continue;
            }
            ("dividend".equalsIgnoreCase(resolved.row().kind()) ? dividendInstrumentIds : tradeInstrumentIds)
                    .add(resolved.instrument().getId());
        }
        lookups.ensureDividends(dividendInstrumentIds);
        lookups.ensureTransactions(tradeInstrumentIds);

        for (ResolvedRow resolved : resolvedRows) {
            ParsedRow row = resolved.row();
            Instrument matchedInstrument = resolved.instrument();
            if (row.error() != null) {
                errorCount++;
                rowDtos.add(new ImportPreviewResponse.ImportRowPreviewDto(
                        row.rowIndex(), "unmatched", null, false, row
                ));
                continue;
            }

            String matchStatus;
            ImportPreviewResponse.MatchedInstrumentDto matchedDto = null;
            boolean isDuplicate = false;

            if (matchedInstrument != null) {
                matchStatus = "matched";
                matchedCount++;
                matchedDto = new ImportPreviewResponse.MatchedInstrumentDto(
                        matchedInstrument.getId(),
                        overrides.type(matchedInstrument),
                        overrides.name(matchedInstrument),
                        overrides.symbol(matchedInstrument),
                        overrides.exchange(matchedInstrument),
                        matchedInstrument.getIsin()
                );

                if ("dividend".equalsIgnoreCase(row.kind())) {
                    // Check duplicate for dividend
                    Holding holding = lookups.holding(matchedInstrument.getId());
                    if (holding != null) {
                        List<Dividend> existingDivs = lookups.dividends(holding);
                        BigDecimal divAmt = row.amount() != null ? row.amount() : row.price();
                        for (Dividend existingDiv : existingDivs) {
                            if (row.tradeDate() != null && row.tradeDate().equals(existingDiv.getPayDate())
                                    && divAmt != null && divAmt.compareTo(existingDiv.getAmount()) == 0) {
                                isDuplicate = true;
                                break;
                            }
                        }
                    }
                } else {
                    // Check duplicate for trade. Zerodha assigns a unique trade_id per
                    // execution (carried as externalRef), so for the tradebook we match on
                    // that alone — the date+type+qty+price heuristic would wrongly flag
                    // legitimate identical fills (e.g. an order split across executions).
                    boolean matchByExternalRefOnly = source == ImportSource.zerodha_tradebook;
                    Holding txnHolding = lookups.holding(matchedInstrument.getId());
                    List<InvestmentTransaction> existingTxns = txnHolding != null ? lookups.transactions(txnHolding) : List.of();

                    for (InvestmentTransaction existingTxn : existingTxns) {
                        if (row.externalRef() != null && existingTxn.getExternalRef() != null
                                && existingTxn.getExternalRef().equalsIgnoreCase(row.externalRef())) {
                            isDuplicate = true;
                            break;
                        }
                        if (!matchByExternalRefOnly
                                && row.tradeDate() != null && row.tradeDate().equals(existingTxn.getTradeDate())
                                && row.type() == existingTxn.getType()
                                && row.quantity() != null && row.quantity().compareTo(existingTxn.getQuantity()) == 0
                                && row.price() != null && row.price().compareTo(existingTxn.getPrice()) == 0) {
                            isDuplicate = true;
                            break;
                        }
                    }
                }

                if (isDuplicate) {
                    duplicateCount++;
                }

            } else {
                matchStatus = "unmatched";
                unmatchedCount++;
            }

            rowDtos.add(new ImportPreviewResponse.ImportRowPreviewDto(
                    row.rowIndex(), matchStatus, matchedDto, isDuplicate, row
            ));
        }

        String note = source == ImportSource.zerodha_tradebook
                ? "Zerodha tradebook does not include itemized charges. Charges were set to null."
                : (source == ImportSource.mf_cas
                        ? "CAMS/KFintech MF CAS: Multiple folios of the same scheme were merged into single broker holdings by ISIN."
                        : (source == ImportSource.groww ? "Groww stock exports do not include itemized charges. Charges were set to null." : null));

        ImportPreviewResponse.SummaryDto summary = new ImportPreviewResponse.SummaryDto(
                parsedRows.size(), matchedCount, unmatchedCount, duplicateCount, errorCount, note
        );

        Map<String, Long> previewSkipReasons = duplicateCount > 0 ? Map.of("duplicate", (long) duplicateCount) : Collections.emptyMap();
        Map<String, Long> previewFailReasons = parsedRows.stream()
                .filter(r -> r.error() != null && !r.error().isBlank())
                .collect(Collectors.groupingBy(ParsedRow::error, Collectors.counting()));

        log.info("Import preview computed: candidates={}, newRows={}, dedupSkipped={}, unresolved={}, failed={}",
                parsedRows.size(), matchedCount, duplicateCount, unmatchedCount, errorCount,
                StructuredArguments.keyValue("event", Events.IMPORT_PREVIEW_COMPUTED),
                StructuredArguments.keyValue("candidates", parsedRows.size()),
                StructuredArguments.keyValue("newRows", matchedCount),
                StructuredArguments.keyValue("dedupSkipped", duplicateCount),
                StructuredArguments.keyValue("unresolved", unmatchedCount),
                StructuredArguments.keyValue("failed", errorCount),
                StructuredArguments.keyValue("skipReasons", previewSkipReasons),
                StructuredArguments.keyValue("failReasons", previewFailReasons));

        return new ImportPreviewResponse(rowDtos, summary);
    }

    public ImportCommitResponse commit(ImportSource source, UUID brokerAccountId, List<ImportCommitRequest.CommitRowDto> rows) {
        Account brokerAccount = accountRepository.findByIdAndUser_Id(brokerAccountId, UserContext.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", brokerAccountId));

        if (brokerAccount.getType() != AccountType.broker) {
            throw new ValidationException("Account must be a broker account");
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        int committed = 0;
        int skipped = 0;
        List<ImportCommitResponse.FailedCommitItem> failedList = new ArrayList<>();
        List<ImportCommitResponse.SkippedCommitItem> skippedItems = new ArrayList<>();
        Set<UUID> touchedInstrumentIds = new HashSet<>();

        // Per-import batches (never one query per row): picked instruments by id, ISIN / alias matches for new
        // instruments, then the holdings, dividends and transactions of every instrument those rows land on.
        ImportLookups lookups = new ImportLookups(brokerAccountId);
        List<ImportCommitRequest.CommitRowDto> activeRows = rows.stream().filter(r -> !r.skip()).toList();
        lookups.loadInstrumentsById(activeRows.stream().map(ImportCommitRequest.CommitRowDto::instrumentId)
                .filter(Objects::nonNull).toList());
        List<ImportCommitRequest.CreateInstrumentDto> newDtos = activeRows.stream()
                .filter(r -> r.instrumentId() == null && r.newInstrument() != null)
                .map(ImportCommitRequest.CommitRowDto::newInstrument).toList();
        lookups.loadIsins(newDtos.stream().map(ImportCommitRequest.CreateInstrumentDto::isin)
                .filter(i -> i != null && !i.isBlank()).map(String::trim).toList());
        lookups.loadAliases(newDtos.stream().map(ImportCommitRequest.CreateInstrumentDto::symbol)
                .filter(sym -> sym != null && !sym.isBlank()).toList());
        Set<UUID> likelyInstrumentIds = new HashSet<>();
        for (ImportCommitRequest.CommitRowDto r : activeRows) {
            Instrument likely = null;
            if (r.instrumentId() != null) {
                likely = lookups.byId(r.instrumentId());
            } else if (r.newInstrument() != null) {
                ImportCommitRequest.CreateInstrumentDto d = r.newInstrument();
                if (d.isin() != null && !d.isin().isBlank()) {
                    likely = lookups.isin(d.isin().trim());
                }
                if (likely == null) {
                    likely = lookups.ownAlias(d.symbol());
                }
            }
            if (likely != null) {
                likelyInstrumentIds.add(lookups.repointed(likely).getId());
            }
        }
        lookups.ensureDividends(likelyInstrumentIds);
        lookups.ensureTransactions(likelyInstrumentIds);

        for (ImportCommitRequest.CommitRowDto rowDto : rows) {
            String scripName = extractScrip(rowDto);
            if (rowDto.skip()) {
                skipped++;
                skippedItems.add(new ImportCommitResponse.SkippedCommitItem(
                        rowDto.rowIndex(), scripName, "Excluded during review"));
                continue;
            }

            try {
                // Resolve or create Instrument
                Instrument instrument = null;
                if (rowDto.instrumentId() != null) {
                    instrument = lookups.byId(rowDto.instrumentId());
                    if (instrument == null) {
                        throw new ResourceNotFoundException("Instrument", rowDto.instrumentId());
                    }
                } else if (rowDto.newInstrument() != null) {
                    ImportCommitRequest.CreateInstrumentDto newInstDto = rowDto.newInstrument();
                    // Idempotent search by ISIN or Symbol+Exchange
                    if (newInstDto.isin() != null && !newInstDto.isin().isBlank()) {
                        instrument = lookups.isin(newInstDto.isin().trim());
                    }
                    if (instrument == null && newInstDto.symbol() != null && !newInstDto.symbol().isBlank()) {
                        instrument = lookups.ownAlias(newInstDto.symbol());
                    }
                    if (instrument == null && newInstDto.symbol() != null && !newInstDto.symbol().isBlank()) {
                        List<Instrument> searchResults = instrumentRepository.searchInstruments(newInstDto.symbol().trim(), null);
                        for (Instrument inst : searchResults) {
                            if (inst.getSymbol() != null && inst.getSymbol().equalsIgnoreCase(newInstDto.symbol().trim())) {
                                if (newInstDto.exchange() == null || inst.getExchange() == null || inst.getExchange().equalsIgnoreCase(newInstDto.exchange().trim())) {
                                    instrument = inst;
                                    break;
                                }
                            }
                        }
                        if (instrument == null && aliasRepository != null) {
                            instrument = lookups.catalogAlias(newInstDto.symbol());
                        }
                    }

                    if (instrument == null) {
                        Instrument newInst = new Instrument();
                        InstrumentType defaultType = (source == ImportSource.mf_cas) ? InstrumentType.mutual_fund : InstrumentType.stock;
                        newInst.setType(newInstDto.type() != null ? newInstDto.type() : defaultType);
                        newInst.setName(newInstDto.name() != null ? newInstDto.name() : newInstDto.symbol());
                        newInst.setSymbol(newInstDto.symbol());
                        newInst.setExchange(newInstDto.exchange() != null ? newInstDto.exchange() : (source == ImportSource.mf_cas ? "MUTUAL_FUND" : "NSE"));
                        newInst.setIsin(newInstDto.isin() != null ? newInstDto.isin().trim() : null);
                        newInst.setAmfiCode(newInstDto.amfiCode());

                        String yahooSym = newInstDto.yahooSymbol();
                        if ((yahooSym == null || yahooSym.isBlank()) && newInst.getType() == InstrumentType.stock) {
                            String ex = newInst.getExchange();
                            yahooSym = newInst.getSymbol() + ("BSE".equalsIgnoreCase(ex) ? ".BO" : ".NS");
                        }
                        newInst.setYahooSymbol(yahooSym);

                        instrument = instrumentRepository.save(newInst);
                        lookups.created(instrument);
                    }
                }

                if (instrument == null) {
                    throw new ValidationException("Row " + rowDto.rowIndex() + ": No instrument provided or created");
                }
                // A row picked (or found) as an instrument the user moved off lands on the one they moved to.
                instrument = lookups.repointed(instrument);

                // Resolve or create Holding (Note: Multiple folios of the same scheme collapse into one holding for (brokerAccount x instrument))
                final Instrument finalInstrument = instrument;
                lookups.ensureDividends(List.of(finalInstrument.getId()));
                lookups.ensureTransactions(List.of(finalInstrument.getId()));
                Holding holding = lookups.holding(finalInstrument.getId());
                if (holding == null) {
                    Holding h = new Holding(brokerAccount, finalInstrument, null);
                    h.setUser(user);
                    holding = holdingRepository.save(h);
                    lookups.holdingCreated(finalInstrument.getId(), holding);
                }

                ImportCommitRequest.ParsedRowData rowData = rowDto.row();
                if (rowData == null) {
                    throw new ValidationException("Row " + rowDto.rowIndex() + ": Missing parsed row data");
                }

                boolean isDividend = rowData.kind() != null && rowData.kind().equalsIgnoreCase("dividend");

                if (isDividend) {
                    BigDecimal divAmount = rowData.amount() != null ? rowData.amount() : rowData.price();
                    if (divAmount == null) {
                        throw new ValidationException("Row " + rowDto.rowIndex() + ": Dividend amount is missing");
                    }

                    // Duplicate check for Dividend
                    boolean isDup = false;
                    List<Dividend> existingDivs = lookups.dividends(holding);
                    for (Dividend existingDiv : existingDivs) {
                        if (rowData.tradeDate() != null && rowData.tradeDate().equals(existingDiv.getPayDate())
                                && divAmount.compareTo(existingDiv.getAmount()) == 0) {
                            isDup = true;
                            break;
                        }
                    }

                    if (isDup) {
                        skipped++;
                        skippedItems.add(new ImportCommitResponse.SkippedCommitItem(
                                rowDto.rowIndex(), scripName, "Duplicate — dividend already in portfolio"));
                        log.info("Skipping commit for dividend row {} as duplicate dividend exists.", rowDto.rowIndex());
                        continue;
                    }

                    Dividend dividend = new Dividend();
                    dividend.setUser(user);
                    dividend.setHolding(holding);
                    dividend.setType(DividendType.dividend);
                    dividend.setAmount(divAmount);
                    dividend.setExDate(rowData.tradeDate());
                    dividend.setPayDate(rowData.tradeDate());
                    dividend.setSource("import");
                    dividend.setNotes(rowData.notes());

                    lookups.dividends(holding).add(dividendRepository.save(dividend));
                    committed++;
                    touchedInstrumentIds.add(finalInstrument.getId());

                } else {
                    // Trade (BUY / SELL). See preview(): Zerodha rows dedupe on trade_id
                    // (externalRef) only; other sources keep the date+type+qty+price fallback.
                    boolean isDup = false;
                    boolean matchByExternalRefOnly = source == ImportSource.zerodha_tradebook;
                    for (InvestmentTransaction existingTxn : lookups.transactions(holding)) {
                        if (rowData.externalRef() != null && existingTxn.getExternalRef() != null
                                && existingTxn.getExternalRef().equalsIgnoreCase(rowData.externalRef())) {
                            isDup = true;
                            break;
                        }
                        if (!matchByExternalRefOnly
                                && rowData.tradeDate() != null && rowData.tradeDate().equals(existingTxn.getTradeDate())
                                && rowData.type() == existingTxn.getType()
                                && rowData.quantity() != null && rowData.quantity().compareTo(existingTxn.getQuantity()) == 0
                                && rowData.price() != null && rowData.price().compareTo(existingTxn.getPrice()) == 0) {
                            isDup = true;
                            break;
                        }
                    }

                    if (isDup) {
                        skipped++;
                        skippedItems.add(new ImportCommitResponse.SkippedCommitItem(
                                rowDto.rowIndex(), scripName, "Duplicate — already in your portfolio"));
                        log.info("Skipping commit for row {} as duplicate transaction exists.", rowDto.rowIndex());
                        continue;
                    }

                    InvestmentTransaction txn = new InvestmentTransaction();
                    txn.setUser(user);
                    txn.setHolding(holding);
                    txn.setType(rowData.type());
                    txn.setQuantity(rowData.quantity());
                    txn.setPrice(rowData.price());
                    txn.setTradeDate(rowData.tradeDate());
                    txn.setSource("import");
                    txn.setExternalRef(rowData.externalRef());
                    txn.setNotes(rowData.notes());

                    applyItemizedCharges(txn, rowData.charges());

                    lookups.transactions(holding).add(transactionRepository.save(txn));
                    committed++;
                    touchedInstrumentIds.add(finalInstrument.getId());
                }

            } catch (Exception e) {
                log.error("Error committing import row " + rowDto.rowIndex(), e);
                failedList.add(new ImportCommitResponse.FailedCommitItem(rowDto.rowIndex(), scripName, e.getMessage()));
            }
        }

        // Auto-fetch latest prices for every instrument touched by this import once it commits,
        // so the UI reflects them without a manual price refresh (handled by PriceRefreshEventListener).
        if (!touchedInstrumentIds.isEmpty()) {
            eventPublisher.publishEvent(new PriceRefreshEvent(touchedInstrumentIds));
        }

        Map<String, Long> skipReasonsMap = skippedItems.stream()
                .filter(i -> i.reason() != null && !i.reason().isBlank())
                .collect(Collectors.groupingBy(ImportCommitResponse.SkippedCommitItem::reason, Collectors.counting()));
        Map<String, Long> failReasonsMap = failedList.stream()
                .filter(f -> f.reason() != null && !f.reason().isBlank())
                .collect(Collectors.groupingBy(ImportCommitResponse.FailedCommitItem::reason, Collectors.counting()));

        log.info("Import commit completed: candidates={}, newRows={}, dedupSkipped={}, failed={}",
                rows.size(), committed, skipped, failedList.size(),
                StructuredArguments.keyValue("event", Events.IMPORT_COMMIT_COMPLETED),
                StructuredArguments.keyValue("candidates", rows.size()),
                StructuredArguments.keyValue("newRows", committed),
                StructuredArguments.keyValue("dedupSkipped", skipped),
                StructuredArguments.keyValue("failed", failedList.size()),
                StructuredArguments.keyValue("skipReasons", skipReasonsMap),
                StructuredArguments.keyValue("failReasons", failReasonsMap));

        return new ImportCommitResponse(committed, skipped, failedList, skippedItems);
    }

    private String extractScrip(ImportCommitRequest.CommitRowDto rowDto) {
        if (rowDto.newInstrument() != null) {
            if (rowDto.newInstrument().name() != null && !rowDto.newInstrument().name().isBlank()) {
                return rowDto.newInstrument().name();
            }
            if (rowDto.newInstrument().symbol() != null && !rowDto.newInstrument().symbol().isBlank()) {
                return rowDto.newInstrument().symbol();
            }
        }
        return null;
    }

    private void applyItemizedCharges(InvestmentTransaction txn, ItemizedChargesDto charges) {
        if (charges != null) {
            txn.setBrokerage(charges.brokerage());
            txn.setStt(charges.stt());
            txn.setExchangeTxnCharges(charges.exchangeTxnCharges());
            txn.setSebiCharges(charges.sebiCharges());
            txn.setStampDuty(charges.stampDuty());
            txn.setGst(charges.gst());
            txn.setDpCharges(charges.dpCharges());
            txn.setOtherCharges(charges.otherCharges());
        }
    }

    private record ResolvedRow(ParsedRow row, Instrument instrument) {}

    /** Oracle caps an IN list at 1000 entries; batch lookups go in chunks of this size. */
    private static final int IN_LIST_CHUNK = 900;

    private static <T> List<List<T>> chunks(Collection<T> items) {
        List<T> list = List.copyOf(items);
        List<List<T>> out = new ArrayList<>();
        for (int from = 0; from < list.size(); from += IN_LIST_CHUNK) {
            out.add(list.subList(from, Math.min(list.size(), from + IN_LIST_CHUNK)));
        }
        return out;
    }

    private static String aliasKey(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * What one import reads from the database, loaded in batches and then served from maps: instruments by id /
     * ISIN, aliases by symbol, and the broker account's holdings with their dividends and transactions. Rows
     * created or saved during the import are registered here so later rows of the same import see them.
     */
    private final class ImportLookups {
        private final UUID brokerAccountId;
        private final Map<String, Instrument> byIsin = new HashMap<>();
        private final Map<UUID, Instrument> byId = new HashMap<>();
        private final Map<String, Instrument> ownAliases = new HashMap<>();
        private final Map<String, Instrument> catalogAliases = new HashMap<>();
        private final Map<UUID, Instrument> repointCache = new HashMap<>();
        private final Set<UUID> holdingsLoaded = new HashSet<>();
        private final Map<UUID, Holding> holdingByInstrument = new HashMap<>();
        private final Set<UUID> dividendsLoaded = new HashSet<>();
        private final Map<UUID, List<Dividend>> dividendsByHolding = new HashMap<>();
        private final Set<UUID> transactionsLoaded = new HashSet<>();
        private final Map<UUID, List<InvestmentTransaction>> transactionsByHolding = new HashMap<>();

        ImportLookups(UUID brokerAccountId) {
            this.brokerAccountId = brokerAccountId;
        }

        void loadIsins(Collection<String> isins) {
            for (List<String> chunk : chunks(new LinkedHashSet<>(isins))) {
                for (Instrument i : instrumentRepository.findByIsinIn(chunk)) {
                    byIsin.put(i.getIsin(), i);
                }
            }
        }

        void loadInstrumentsById(Collection<UUID> ids) {
            for (List<UUID> chunk : chunks(new LinkedHashSet<>(ids))) {
                for (Instrument i : instrumentRepository.findAllById(chunk)) {
                    byId.put(i.getId(), i);
                }
            }
        }

        void loadAliases(Collection<String> symbols) {
            Set<String> keys = symbols.stream().map(ImportService::aliasKey).collect(Collectors.toCollection(LinkedHashSet::new));
            UUID userId = UserContext.getCurrentUserId();
            if (aliasRepository == null || keys.isEmpty()) {
                return;
            }
            for (List<String> chunk : chunks(keys)) {
                if (userId != null) {
                    for (InstrumentAlias a : aliasRepository.findOwnedBySymbolsUpper(userId, chunk)) {
                        ownAliases.putIfAbsent(aliasKey(a.getOldSymbol()), a.getInstrument());
                    }
                }
                for (InstrumentAlias a : aliasRepository.findCatalogBySymbolsUpper(chunk)) {
                    catalogAliases.putIfAbsent(aliasKey(a.getOldSymbol()), a.getInstrument());
                }
            }
        }

        Instrument isin(String isin) {
            return byIsin.get(isin);
        }

        Instrument byId(UUID id) {
            return byId.get(id);
        }

        /** The signed-in user's own alias of {@code symbol} (set when they repointed it), or null. */
        Instrument ownAlias(String symbol) {
            if (aliasRepository == null || UserContext.getCurrentUserId() == null || symbol == null || symbol.isBlank()) {
                return null;
            }
            return ownAliases.get(aliasKey(symbol));
        }

        Instrument catalogAlias(String symbol) {
            return catalogAliases.get(aliasKey(symbol));
        }

        /** {@link ImportService#repointed}, asked once per instrument for the whole import. */
        Instrument repointed(Instrument instrument) {
            if (instrument == null || instrument.getId() == null) {
                return ImportService.this.repointed(instrument);
            }
            return repointCache.computeIfAbsent(instrument.getId(), k -> ImportService.this.repointed(instrument));
        }

        /** An instrument saved by this import: findable by its ISIN by later rows, and with no holding yet. */
        void created(Instrument instrument) {
            if (instrument.getIsin() != null) {
                byIsin.put(instrument.getIsin(), instrument);
            }
            holdingsLoaded.add(instrument.getId());
        }

        void ensureHoldings(Collection<UUID> instrumentIds) {
            Set<UUID> missing = new LinkedHashSet<>(instrumentIds);
            missing.removeAll(holdingsLoaded);
            for (List<UUID> chunk : chunks(missing)) {
                for (Holding h : holdingRepository.findByBrokerAccountIdAndInstrumentIdIn(brokerAccountId, chunk)) {
                    holdingByInstrument.put(h.getInstrument().getId(), h);
                }
            }
            holdingsLoaded.addAll(missing);
        }

        void ensureDividends(Collection<UUID> instrumentIds) {
            ensureHoldings(instrumentIds);
            List<UUID> holdingIds = new ArrayList<>();
            for (UUID instrumentId : new LinkedHashSet<>(instrumentIds)) {
                Holding h = holdingByInstrument.get(instrumentId);
                if (h != null && dividendsLoaded.add(h.getId())) {
                    holdingIds.add(h.getId());
                    dividendsByHolding.put(h.getId(), new ArrayList<>());
                }
            }
            for (List<UUID> chunk : chunks(holdingIds)) {
                for (Dividend d : dividendRepository.findByHoldingIdInOrderByPayDateDescCreatedAtDesc(chunk)) {
                    dividendsByHolding.get(d.getHolding().getId()).add(d);
                }
            }
        }

        void ensureTransactions(Collection<UUID> instrumentIds) {
            ensureHoldings(instrumentIds);
            List<UUID> holdingIds = new ArrayList<>();
            for (UUID instrumentId : new LinkedHashSet<>(instrumentIds)) {
                Holding h = holdingByInstrument.get(instrumentId);
                if (h != null && transactionsLoaded.add(h.getId())) {
                    holdingIds.add(h.getId());
                    transactionsByHolding.put(h.getId(), new ArrayList<>());
                }
            }
            for (List<UUID> chunk : chunks(holdingIds)) {
                for (InvestmentTransaction t : transactionRepository.findByHoldingIdInOrderByTradeDateAscCreatedAtAsc(chunk)) {
                    transactionsByHolding.get(t.getHolding().getId()).add(t);
                }
            }
        }

        /** The broker account's holding of the instrument, or null (callers load it with the ensure methods first). */
        Holding holding(UUID instrumentId) {
            return holdingByInstrument.get(instrumentId);
        }

        /** A holding saved by this import: later rows of the same instrument reuse it, with nothing recorded yet. */
        void holdingCreated(UUID instrumentId, Holding holding) {
            holdingByInstrument.put(instrumentId, holding);
            dividendsLoaded.add(holding.getId());
            dividendsByHolding.put(holding.getId(), new ArrayList<>());
            transactionsLoaded.add(holding.getId());
            transactionsByHolding.put(holding.getId(), new ArrayList<>());
        }

        /** The holding's dividends (mutable: add what the import saves). */
        List<Dividend> dividends(Holding holding) {
            return dividendsByHolding.computeIfAbsent(holding.getId(), k -> new ArrayList<>());
        }

        /** The holding's transactions (mutable: add what the import saves). */
        List<InvestmentTransaction> transactions(Holding holding) {
            return transactionsByHolding.computeIfAbsent(holding.getId(), k -> new ArrayList<>());
        }
    }
}
