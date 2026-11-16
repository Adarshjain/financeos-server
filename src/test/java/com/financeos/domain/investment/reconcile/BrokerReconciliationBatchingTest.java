package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.FnoTradePreviewDto;
import com.financeos.api.investment.dto.ImportCommitResponse;
import com.financeos.api.investment.dto.ReconcileCommitRequest;
import com.financeos.api.investment.dto.ReconcilePreviewResponse;
import com.financeos.api.investment.dto.CommitFnoTradeDto;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentAlias;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentRepointMap;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.UserInstrumentRepoint;
import com.financeos.domain.instrument.UserInstrumentRepointRepository;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.charges.ChargeCalculator;
import com.financeos.domain.investment.fno.FnoTradeRepository;
import com.financeos.domain.investment.fno.FnoTradeService;
import com.financeos.domain.investment.imports.ZerodhaTradebookParser;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Preview and commit read their lookups in batches: the answers are what the per-row queries gave (duplicates,
 * alias hits and misses, repointed instruments, new and existing F&O trades) and the number of repository reads
 * does not grow with the number of rows.
 *
 * Row i of the tradebook is of kind (i - 1) % 6: 0 a held ISIN, 1 a user alias, 2 a catalog symbol,
 * 3 a catalog alias, 4 nothing known, 5 an ISIN of an instrument the user moved off.
 */
class BrokerReconciliationBatchingTest {

    private final UUID user = UUID.randomUUID();
    private final UUID brokerAccountId = UUID.randomUUID();

    private InstrumentRepository instrumentRepository;
    private InstrumentAliasRepository aliasRepository;
    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private FnoTradeRepository fnoTradeRepository;
    private UserInstrumentRepointRepository repointRepository;
    private ZerodhaTaxPnlParser taxPnlParser;
    private BrokerReconciliationService service;

    private Instrument held;
    private Instrument viaOwnAlias;
    private Instrument viaCatalogSymbol;
    private Instrument viaCatalogAlias;
    private Instrument movedFrom;
    private Instrument movedTo;
    private final List<InvestmentTransaction> existing = new ArrayList<>();
    private final Set<String> existingFnoRefs = new HashSet<>();

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        aliasRepository = mock(InstrumentAliasRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        fnoTradeRepository = mock(FnoTradeRepository.class);
        repointRepository = mock(UserInstrumentRepointRepository.class);
        taxPnlParser = mock(ZerodhaTaxPnlParser.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        service = new BrokerReconciliationService(new ZerodhaTradebookParser(), taxPnlParser,
                new GrowwOrderHistoryParser(), new GrowwCapitalGainsParser(), new ChargeCalculator(), instrumentRepository,
                aliasRepository, holdingRepository, transactionRepository, accountRepository,
                mock(UserRepository.class), mock(InstrumentSearchService.class), mock(HoldingsSnapshotParser.class),
                mock(TradeSettlementClassificationRepository.class), mock(ApplicationEventPublisher.class),
                fnoTradeRepository, mock(FnoTradeService.class));
        Account account = new Account();
        account.setId(brokerAccountId);
        account.setType(AccountType.broker);
        account.setName("Test Broker");
        when(accountRepository.findByIdAndUser_Id(eq(brokerAccountId), any())).thenReturn(Optional.of(account));
        UserContext.setCurrentUserId(user);

        held = instrument("HELDCO", "INE111A01011");
        viaOwnAlias = instrument("OWNCO", "INE222A01011");
        viaCatalogSymbol = instrument("CATS", "INE333A01011");
        viaCatalogAlias = instrument("CATNEW", "INE444A01011");
        movedFrom = instrument("MOVEDOLD", "INE555A01011");
        movedTo = instrument("MOVEDNEW", "INE666A01011");

        when(instrumentRepository.findByIsinIn(any())).thenAnswer(inv -> {
            java.util.Collection<String> isins = inv.getArgument(0);
            return List.of(held, movedFrom).stream().filter(i -> isins.contains(i.getIsin())).toList();
        });
        when(instrumentRepository.findBySymbolUpperIn(any())).thenAnswer(inv -> {
            java.util.Collection<String> symbols = inv.getArgument(0);
            return List.of(viaCatalogSymbol).stream().filter(i -> symbols.contains(i.getSymbol().toUpperCase())).toList();
        });
        when(instrumentRepository.findAllById(any())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(0);
            return List.of(held, viaOwnAlias, viaCatalogSymbol, viaCatalogAlias, movedFrom, movedTo).stream()
                    .filter(i -> ids.contains(i.getId())).toList();
        });
        for (Instrument i : List.of(held, viaOwnAlias, viaCatalogSymbol, viaCatalogAlias, movedFrom, movedTo)) {
            when(instrumentRepository.findById(i.getId())).thenReturn(Optional.of(i));
        }
        InstrumentAlias own = new InstrumentAlias(viaOwnAlias, "OLDA", "Old A", "USER_EDIT");
        own.setUserId(user);
        when(aliasRepository.findOwnedBySymbolsUpper(eq(user), any())).thenAnswer(inv -> {
            java.util.Collection<String> symbols = inv.getArgument(1);
            return symbols.contains("OLDA") ? List.of(own) : List.of();
        });
        InstrumentAlias catalog = new InstrumentAlias(viaCatalogAlias, "OLDC", "Old C", "CATALOG");
        when(aliasRepository.findCatalogBySymbolsUpper(any())).thenAnswer(inv -> {
            java.util.Collection<String> symbols = inv.getArgument(0);
            return symbols.contains("OLDC") ? List.of(catalog) : List.of();
        });
        when(repointRepository.findByUserId(user))
                .thenReturn(List.of(new UserInstrumentRepoint(user, movedFrom.getId(), movedTo.getId())));
        service.setRepointMap(new InstrumentRepointMap(repointRepository, instrumentRepository));

        // Existing trades by external ref: rows 1-4 and 6 of the tradebook are already imported; row 12 sits
        // on the instrument the user moved off, which is not where its row lands.
        existing.add(txn(held, "1"));
        existing.add(txn(viaOwnAlias, "2"));
        existing.add(txn(viaCatalogSymbol, "3"));
        existing.add(txn(viaCatalogAlias, "4"));
        existing.add(txn(movedTo, "6"));
        existing.add(txn(movedFrom, "12"));
        when(transactionRepository.findByBrokerAccountAndInstrumentIds(eq(brokerAccountId), any())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(1);
            return existing.stream().filter(t -> ids.contains(t.getHolding().getInstrument().getId())).toList();
        });
        when(fnoTradeRepository.findExistingExternalRefs(eq(brokerAccountId), any())).thenAnswer(inv -> {
            java.util.Collection<String> refs = inv.getArgument(1);
            return refs.stream().filter(existingFnoRefs::contains).toList();
        });
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(any(), any())).thenReturn(Optional.empty());
        when(holdingRepository.save(any(Holding.class))).thenAnswer(inv -> {
            Holding h = inv.getArgument(0);
            h.setId(UUID.randomUUID());
            return h;
        });
        when(transactionRepository.save(any(InvestmentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Instrument instrument(String symbol, String isin) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setSymbol(symbol);
        i.setName(symbol + " Ltd");
        i.setExchange("NSE");
        i.setIsin(isin);
        return i;
    }

    private static InvestmentTransaction txn(Instrument instrument, String ref) {
        Holding holding = new Holding();
        holding.setInstrument(instrument);
        InvestmentTransaction t = new InvestmentTransaction();
        t.setHolding(holding);
        t.setExternalRef(ref);
        t.setTradeDate(LocalDate.of(2026, 2, 15));
        t.setType(InvestmentTransactionType.buy);
        t.setQuantity(BigDecimal.TEN);
        t.setPrice(new BigDecimal("300"));
        return t;
    }

    private static String tradebookRow(int i) {
        String[] symbolIsin = switch ((i - 1) % 6) {
            case 0 -> new String[]{"HELDCO", "INE111A01011"};
            case 1 -> new String[]{"OLDA", ""};
            case 2 -> new String[]{"CATS", ""};
            case 3 -> new String[]{"OLDC", ""};
            case 4 -> new String[]{"NOPE", ""};
            default -> new String[]{"MOVEDOLD", "INE555A01011"};
        };
        return symbolIsin[0] + "," + symbolIsin[1] + ",2026-02-15,NSE,EQ,EQ,buy,false,10,300.0," + i + "," + (1000 + i)
                + ",2026-02-15 10:00:00\n";
    }

    private ReconcilePreviewResponse preview(int rows, int fnoExits) {
        StringBuilder csv = new StringBuilder("symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time\n");
        for (int i = 1; i <= rows; i++) csv.append(tradebookRow(i));
        List<ZerodhaTaxPnlParser.TaxPnlExit> exits = new ArrayList<>();
        for (int k = 0; k < fnoExits; k++) {
            exits.add(new ZerodhaTaxPnlParser.TaxPnlExit("FNO", null, "NIFTYFUT" + k, LocalDate.of(2026, 1, 5),
                    LocalDate.of(2026, 1, 20), new BigDecimal(25 + k), new BigDecimal("1000"), new BigDecimal("1100"),
                    new BigDecimal("100"), null));
            if (k % 2 == 0) existingFnoRefs.add(fnoRef(k));
        }
        when(taxPnlParser.parse(any())).thenReturn(exits);
        return service.preview(Broker.zerodha, brokerAccountId,
                List.of(new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))),
                List.of(new ByteArrayInputStream(new byte[0])));
    }

    private static String fnoRef(int k) {
        return "FNO_NIFTYFUT" + k + "_2026-01-05_2026-01-20_" + (25 + k);
    }

    private UUID expectedInstrument(int row) {
        return switch ((row - 1) % 6) {
            case 0 -> held.getId();
            case 1 -> viaOwnAlias.getId();
            case 2 -> viaCatalogSymbol.getId();
            case 3 -> viaCatalogAlias.getId();
            case 4 -> null;
            default -> movedTo.getId();
        };
    }

    /** Repository reads of the batched lookups across every mock (writes and per-instrument finds excluded). */
    private int reads() {
        int n = 0;
        for (Object m : List.of(instrumentRepository, aliasRepository, holdingRepository, transactionRepository,
                fnoTradeRepository, repointRepository)) {
            n += (int) Mockito.mockingDetails(m).getInvocations().stream()
                    .filter(inv -> !Set.of("save", "findById").contains(inv.getMethod().getName()))
                    .count();
        }
        return n;
    }

    // ------------------------------------------------------------------ preview

    @Test
    void previewAnswersAreTheOnesThePerRowQueriesGave() {
        ReconcilePreviewResponse res = preview(12, 4);

        assertEquals(12, res.executions().size());
        Set<String> duplicateRefs = res.executions().stream().filter(ReconcilePreviewResponse.ReconciledExecutionDto::isDuplicate)
                .map(ReconcilePreviewResponse.ReconciledExecutionDto::externalRef).collect(Collectors.toSet());
        assertEquals(Set.of("1", "2", "3", "4", "6"), duplicateRefs);
        for (ReconcilePreviewResponse.ReconciledExecutionDto e : res.executions()) {
            int row = Integer.parseInt(e.externalRef());
            UUID expected = expectedInstrument(row);
            if (expected == null) {
                assertNull(e.matchedInstrument(), "row " + row);
                assertTrue(e.suggestedInstrument() != null, "row " + row);
            } else {
                assertEquals(expected, e.matchedInstrument().id(), "row " + row);
                assertNull(e.suggestedInstrument(), "row " + row);
            }
        }
        Map<String, Boolean> fno = res.fnoTrades().stream()
                .collect(Collectors.toMap(FnoTradePreviewDto::externalRef, FnoTradePreviewDto::isDuplicate));
        assertEquals(Map.of(fnoRef(0), true, fnoRef(1), false, fnoRef(2), true, fnoRef(3), false), fno);
        verify(transactionRepository, never()).findFilteredTransactions(any(), any(), any(), any(), any());
        verify(fnoTradeRepository, never()).existsByBrokerAccountIdAndExternalRef(any(), any());
        verify(aliasRepository, never()).findFirstByOldSymbolIgnoreCaseAndUserId(any(), any());
        verify(aliasRepository, never()).findFirstByOldSymbolIgnoreCaseAndUserIdIsNull(any());
        verify(instrumentRepository, never()).searchInstruments(any(), any());
    }

    @Test
    void previewReadsDoNotGrowWithTheNumberOfRows() {
        preview(12, 4);
        int small = reads();
        Mockito.clearInvocations(instrumentRepository, aliasRepository, holdingRepository, transactionRepository,
                fnoTradeRepository, repointRepository);
        existingFnoRefs.clear();

        ReconcilePreviewResponse res = preview(120, 40);
        int large = reads();

        System.out.println("preview repository reads: 12 rows / 4 F&O = " + small + ", 120 rows / 40 F&O = " + large);
        assertEquals(120, res.executions().size());
        assertEquals(small, large);
    }

    // ------------------------------------------------------------------ commit

    private ReconcileCommitRequest.CommitExecutionDto commitRow(int row, String ref, UUID instrumentId, boolean skip) {
        return new ReconcileCommitRequest.CommitExecutionDto(row, LocalDate.of(2026, 2, 15), InvestmentTransactionType.buy,
                null, "SYM", null, "NSE", BigDecimal.TEN, new BigDecimal("300"), null, ref, instrumentId, null, skip);
    }

    private ReconcileCommitRequest commitRequest(int rows, int fnoTrades) {
        List<ReconcileCommitRequest.CommitExecutionDto> executions = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            UUID id = switch ((i - 1) % 6) {
                case 0 -> held.getId();
                case 1 -> viaOwnAlias.getId();
                case 2 -> viaCatalogSymbol.getId();
                case 3 -> viaCatalogAlias.getId();
                case 4 -> null;
                default -> movedFrom.getId();
            };
            if (id == null) {
                // A row the review left unmatched: the symbol is looked up by the catalog (alias miss, catalog miss).
                executions.add(new ReconcileCommitRequest.CommitExecutionDto(i, LocalDate.of(2026, 2, 15),
                        InvestmentTransactionType.buy, null, "OLDA", null, "NSE", BigDecimal.TEN, new BigDecimal("300"),
                        null, String.valueOf(i), null, null, false));
            } else {
                executions.add(commitRow(i, String.valueOf(i), id, false));
            }
        }
        List<CommitFnoTradeDto> fno = new ArrayList<>();
        for (int k = 0; k < fnoTrades; k++) {
            if (k % 2 == 0) existingFnoRefs.add(fnoRef(k));
            fno.add(new CommitFnoTradeDto("NIFTYFUT" + k, null, null, null, null, null, new BigDecimal(25 + k),
                    new BigDecimal("1000"), new BigDecimal("1100"), BigDecimal.ZERO, LocalDate.of(2026, 1, 5),
                    LocalDate.of(2026, 1, 20), fnoRef(k), false));
        }
        return new ReconcileCommitRequest(Broker.zerodha, brokerAccountId, executions, List.of(), fno);
    }

    @Test
    void commitSkipsExistingTradesAndTradesRepeatedInTheSameRequest() {
        // Rows 1-4 and 6 are already imported; row 5 (alias OLDA, own alias) lands on its instrument as new.
        ImportCommitResponse res = service.commit(commitRequest(6, 4));
        assertEquals(1, res.committed());
        assertEquals(5 + 2, res.skipped());
        assertTrue(res.failed().isEmpty());

        // Two new rows with one ref on one instrument: the second is a duplicate of the first.
        ImportCommitResponse twice = service.commit(new ReconcileCommitRequest(Broker.zerodha, brokerAccountId,
                List.of(commitRow(1, "NEW-1", held.getId(), false), commitRow(2, "NEW-1", held.getId(), false),
                        commitRow(3, "NEW-2", held.getId(), false)), List.of(), List.of()));
        assertEquals(2, twice.committed());
        assertEquals(1, twice.skipped());
        assertFalse(twice.skippedItems().isEmpty());
    }

    @Test
    void commitReadsDoNotGrowWithTheNumberOfRows() {
        service.commit(commitRequest(12, 4));
        int small = reads();
        Mockito.clearInvocations(instrumentRepository, aliasRepository, holdingRepository, transactionRepository,
                fnoTradeRepository, repointRepository);
        existingFnoRefs.clear();

        ImportCommitResponse res = service.commit(commitRequest(120, 40));
        int large = reads();

        System.out.println("commit repository reads: 12 rows / 4 F&O = " + small + ", 120 rows / 40 F&O = " + large);
        assertTrue(res.committed() > 0);
        assertEquals(small, large);
        verify(transactionRepository, never()).findFilteredTransactions(any(), any(), any(), any(), any());
        verify(fnoTradeRepository, never()).existsByBrokerAccountIdAndExternalRef(any(), any());
    }

    @Test
    void commitRowsResolvedByIsinOrSymbolLoadTheirTransactionsUpFront() {
        List<ReconcileCommitRequest.CommitExecutionDto> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            // ISIN-only rows of the held instrument, of the moved-off one (repointed), and a symbol-only catalog row.
            String[] sym = switch (i % 3) {
                case 0 -> new String[]{"HELDCO", "INE111A01011"};
                case 1 -> new String[]{"MOVEDOLD", "INE555A01011"};
                default -> new String[]{"CATS", null};
            };
            rows.add(new ReconcileCommitRequest.CommitExecutionDto(i, LocalDate.of(2026, 2, 15),
                    InvestmentTransactionType.buy, null, sym[0], sym[1], "NSE", BigDecimal.TEN, new BigDecimal("300"),
                    null, "Z" + i, null, null, false));
        }
        ImportCommitResponse res = service.commit(new ReconcileCommitRequest(Broker.zerodha, brokerAccountId, rows,
                List.of(), List.of()));

        assertEquals(30, res.committed());
        verify(transactionRepository, Mockito.times(1)).findByBrokerAccountAndInstrumentIds(any(), any());
    }
}
