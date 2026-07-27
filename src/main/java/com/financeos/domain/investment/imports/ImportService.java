package com.financeos.domain.investment.imports;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final List<ImportParser> parsers;
    private final InstrumentRepository instrumentRepository;
    private final HoldingRepository holdingRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public ImportService(List<ImportParser> parsers,
                         InstrumentRepository instrumentRepository,
                         HoldingRepository holdingRepository,
                         InvestmentTransactionRepository transactionRepository,
                         DividendRepository dividendRepository,
                         AccountRepository accountRepository,
                         UserRepository userRepository) {
        this.parsers = parsers;
        this.instrumentRepository = instrumentRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.dividendRepository = dividendRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ImportPreviewResponse preview(InputStream inputStream, ImportSource source, UUID brokerAccountId) {
        return preview(inputStream, source, brokerAccountId, null);
    }

    @Transactional(readOnly = true)
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

            // Resolve instrument
            Instrument matchedInstrument = null;
            if (row.parsedIsin() != null && !row.parsedIsin().isBlank()) {
                matchedInstrument = instrumentRepository.findByIsin(row.parsedIsin()).orElse(null);
            }

            if (matchedInstrument == null && row.parsedSymbol() != null && !row.parsedSymbol().isBlank()) {
                List<Instrument> searchResults = instrumentRepository.searchInstruments(row.parsedSymbol(), null);
                for (Instrument inst : searchResults) {
                    if (inst.getSymbol() != null && inst.getSymbol().equalsIgnoreCase(row.parsedSymbol())) {
                        if (row.exchange() == null || inst.getExchange() == null || inst.getExchange().equalsIgnoreCase(row.exchange())) {
                            matchedInstrument = inst;
                            break;
                        }
                    }
                }
            }

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
                    // Check duplicate for trade
                    Page<InvestmentTransaction> existingTxnsPage = transactionRepository.findFilteredTransactions(
                            brokerAccountId, matchedInstrument.getId(), null, Pageable.unpaged());

                    for (InvestmentTransaction existingTxn : existingTxnsPage.getContent()) {
                        if (row.externalRef() != null && existingTxn.getExternalRef() != null
                                && existingTxn.getExternalRef().equalsIgnoreCase(row.externalRef())) {
                            isDuplicate = true;
                            break;
                        }
                        if (row.tradeDate() != null && row.tradeDate().equals(existingTxn.getTradeDate())
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

        for (ImportCommitRequest.CommitRowDto rowDto : rows) {
            if (rowDto.skip()) {
                skipped++;
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

                } else {
                    // Trade (BUY / SELL)
                    boolean isDup = false;
                    Page<InvestmentTransaction> existingTxnsPage = transactionRepository.findFilteredTransactions(
                            brokerAccountId, finalInstrument.getId(), null, Pageable.unpaged());

                    for (InvestmentTransaction existingTxn : existingTxnsPage.getContent()) {
                        if (rowData.externalRef() != null && existingTxn.getExternalRef() != null
                                && existingTxn.getExternalRef().equalsIgnoreCase(rowData.externalRef())) {
                            isDup = true;
                            break;
                        }
                        if (rowData.tradeDate() != null && rowData.tradeDate().equals(existingTxn.getTradeDate())
                                && rowData.type() == existingTxn.getType()
                                && rowData.quantity() != null && rowData.quantity().compareTo(existingTxn.getQuantity()) == 0
                                && rowData.price() != null && rowData.price().compareTo(existingTxn.getPrice()) == 0) {
                            isDup = true;
                            break;
                        }
                    }

                    if (isDup) {
                        skipped++;
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
                }

            } catch (Exception e) {
                log.error("Error committing import row " + rowDto.rowIndex(), e);
                failedList.add(new ImportCommitResponse.FailedCommitItem(rowDto.rowIndex(), e.getMessage()));
            }
        }

        return new ImportCommitResponse(committed, skipped, failedList);
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
