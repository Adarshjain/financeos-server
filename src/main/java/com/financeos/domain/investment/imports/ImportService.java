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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
        Account brokerAccount = accountRepository.findById(brokerAccountId)
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
        int matchedCount = 0;
        int unmatchedCount = 0;
        int duplicateCount = 0;
        int errorCount = 0;

        for (ParsedRow row : parsedRows) {
            if (row.error() != null) {
                errorCount++;
                rowDtos.add(new ImportPreviewResponse.ImportRowPreviewDto(
                        row.rowIndex(), "unmatched", null, false, row
                ));
                continue;
            }

            String resolveSource = "local";
            String resolveOutcome = "unmatched";
            int resolveCandidateCount = 0;

            // Resolve instrument
            Instrument matchedInstrument = null;
            if (row.parsedIsin() != null && !row.parsedIsin().isBlank()) {
                matchedInstrument = instrumentRepository.findByIsin(row.parsedIsin()).orElse(null);
                if (matchedInstrument != null) {
                    resolveSource = "isin";
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
                    matchedInstrument = aliasRepository.findFirstByOldSymbolIgnoreCase(row.parsedSymbol().trim())
                            .map(InstrumentAlias::getInstrument)
                            .orElse(null);
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

                        if (bestCandidate != null) {
                            InstrumentResponse resolved = instrumentSearchService.resolve(new ResolveInstrumentRequest(
                                    bestCandidate.type(),
                                    bestCandidate.name(),
                                    bestCandidate.symbol(),
                                    bestCandidate.exchange(),
                                    bestCandidate.isin() != null ? bestCandidate.isin() : row.parsedIsin(),
                                    bestCandidate.amfiCode(),
                                    bestCandidate.yahooSymbol(),
                                    bestCandidate.currency(),
                                    bestCandidate.existingInstrumentId()
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
            }

            log.info("Instrument resolve: isin={}, symbol={}, source={}, outcome={}",
                    row.parsedIsin(), row.parsedSymbol(), resolveSource, resolveOutcome,
                    StructuredArguments.keyValue("event", Events.INSTRUMENT_RESOLVE),
                    StructuredArguments.keyValue("isin", row.parsedIsin() != null ? row.parsedIsin() : ""),
                    StructuredArguments.keyValue("symbol", row.parsedSymbol() != null ? row.parsedSymbol() : ""),
                    StructuredArguments.keyValue("source", resolveSource),
                    StructuredArguments.keyValue("outcome", resolveOutcome),
                    StructuredArguments.keyValue("candidateCount", resolveCandidateCount));

            String matchStatus;
            ImportPreviewResponse.MatchedInstrumentDto matchedDto = null;
            boolean isDuplicate = false;

            if (matchedInstrument != null) {
                matchStatus = "matched";
                matchedCount++;
                matchedDto = new ImportPreviewResponse.MatchedInstrumentDto(
                        matchedInstrument.getId(),
                        matchedInstrument.getType(),
                        matchedInstrument.getName(),
                        matchedInstrument.getSymbol(),
                        matchedInstrument.getExchange(),
                        matchedInstrument.getIsin()
                );

                if ("dividend".equalsIgnoreCase(row.kind())) {
                    // Check duplicate for dividend
                    Optional<Holding> holdingOpt = holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccountId, matchedInstrument.getId());
                    if (holdingOpt.isPresent()) {
                        List<Dividend> existingDivs = dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holdingOpt.get().getId());
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
                    Page<InvestmentTransaction> existingTxnsPage = transactionRepository.findFilteredTransactions(
                            brokerAccountId, matchedInstrument.getId(), null, null, Pageable.unpaged());

                    for (InvestmentTransaction existingTxn : existingTxnsPage.getContent()) {
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
        Account brokerAccount = accountRepository.findById(brokerAccountId)
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
                    instrument = instrumentRepository.findById(rowDto.instrumentId())
                            .orElseThrow(() -> new ResourceNotFoundException("Instrument", rowDto.instrumentId()));
                } else if (rowDto.newInstrument() != null) {
                    ImportCommitRequest.CreateInstrumentDto newInstDto = rowDto.newInstrument();
                    // Idempotent search by ISIN or Symbol+Exchange
                    if (newInstDto.isin() != null && !newInstDto.isin().isBlank()) {
                        instrument = instrumentRepository.findByIsin(newInstDto.isin().trim()).orElse(null);
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
                            instrument = aliasRepository.findFirstByOldSymbolIgnoreCase(newInstDto.symbol().trim())
                                    .map(InstrumentAlias::getInstrument)
                                    .orElse(null);
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
                    }
                }

                if (instrument == null) {
                    throw new ValidationException("Row " + rowDto.rowIndex() + ": No instrument provided or created");
                }

                // Resolve or create Holding (Note: Multiple folios of the same scheme collapse into one holding for (brokerAccount x instrument))
                final Instrument finalInstrument = instrument;
                Holding holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), finalInstrument.getId())
                        .orElseGet(() -> {
                            Holding h = new Holding(brokerAccount, finalInstrument, null);
                            h.setUser(user);
                            return holdingRepository.save(h);
                        });

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
                    List<Dividend> existingDivs = dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(holding.getId());
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

                    dividendRepository.save(dividend);
                    committed++;
                    touchedInstrumentIds.add(finalInstrument.getId());

                } else {
                    // Trade (BUY / SELL). See preview(): Zerodha rows dedupe on trade_id
                    // (externalRef) only; other sources keep the date+type+qty+price fallback.
                    boolean isDup = false;
                    boolean matchByExternalRefOnly = source == ImportSource.zerodha_tradebook;
                    Page<InvestmentTransaction> existingTxnsPage = transactionRepository.findFilteredTransactions(
                            brokerAccountId, finalInstrument.getId(), null, null, Pageable.unpaged());

                    for (InvestmentTransaction existingTxn : existingTxnsPage.getContent()) {
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

                    transactionRepository.save(txn);
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
}
