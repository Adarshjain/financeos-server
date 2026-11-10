package com.financeos.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The parser version reaches the statement on create and on a reconciling refresh, are left alone by
 * a fill-blanks refresh. The snapshot itself is never written by the persistence transaction (see StatementParseRecords#saveQuietly).
 */
class StatementPersistenceParseRecordTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final String SNAPSHOT = "{\"meta\":{\"bank\":\"SECRET-BANK-NARRATION\"}}";

    private final StatementRepository repository = mock(StatementRepository.class);
    private final StatementTransactionRepository links = mock(StatementTransactionRepository.class);
    private final StatementPersistenceService service = new StatementPersistenceService(repository, links, mock(ApplicationEventPublisher.class));
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(repository.save(any(Statement.class))).thenAnswer(inv -> {
            Statement s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });
    }

    private static StatementDraft draft(Integer version, String snapshot) {
        return new StatementDraft("bank_account", START, END, new BigDecimal("1000"), new BigDecimal("1500"), "HDFC", "1234", 3, 0,
                new BigDecimal("200"), new BigDecimal("700"), "balance_chain", new BigDecimal("100"), true,
                StatementVerdict.AUTO_INGEST, null, null, Set.of(), version, snapshot);
    }

    private Optional<Statement> create(StatementDraft d) {
        User user = new User();
        user.setId(UUID.randomUUID());
        Account account = new Account();
        account.setId(accountId);
        return service.createIfNew(user, account, StatementSource.file_upload, "s.pdf", "sha", d);
    }

    private Statement existing(Integer version) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(new User());
        s.setStatementType("bank_account");
        s.setVerdict(StatementVerdict.NEEDS_REVIEW);
        s.setPeriodStart(START);
        s.setPeriodEnd(END);
        s.setOpeningBalance(new BigDecimal("10"));
        s.setClosingBalance(new BigDecimal("20"));
        s.setParserVersion(version);
        s.setParseRecordedAt(version == null ? null : Instant.parse("2026-01-01T00:00:00Z"));
        s.setCreatedAt(Instant.now());
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));
        return s;
    }

    // ---- create

    @Test
    void aNewStatementStoresTheVersionTheTimeAndTheSnapshot() {
        Instant before = Instant.now();

        Statement saved = create(draft(3, SNAPSHOT)).orElseThrow();

        assertThat(saved.getParserVersion()).isEqualTo(3);
        assertThat(saved.getParseRecordedAt()).isBetween(before, Instant.now());
    }

    @Test
    void aDraftNotFromTheParserLeavesTheRecordEmpty() {
        Statement saved = create(draft(null, null)).orElseThrow();

        assertThat(saved.getParserVersion()).isNull();
        assertThat(saved.getParseRecordedAt()).isNull();
    }

    @Test
    void aVersionWithoutASnapshotStoresTheVersionOnly() {
        Statement saved = create(draft(2, null)).orElseThrow();

        assertThat(saved.getParserVersion()).isEqualTo(2);
    }

    @Test
    void aSnapshotOfExactlyTwoMegabytesIsHandedOver() {
        String atLimit = "x".repeat(2 * 1024 * 1024);

        Statement saved = create(draft(1, atLimit)).orElseThrow();

    }

    @Test
    void anOversizeSnapshotStillKeepsTheVersionAndIsHandedToTheRecordsServiceToDrop() {
        String big = "SECRET-NARRATION" + "x".repeat(2 * 1024 * 1024);

        Statement saved = create(draft(1, big)).orElseThrow();

        assertThat(saved.getParserVersion()).isEqualTo(1);
        assertThat(saved.getParseRecordedAt()).isNotNull();

    }

    @Test
    void aDuplicateCreatesNothingAndRecordsNothing() {
        when(repository.existsByAccountIdAndFileSha256(accountId, "sha")).thenReturn(true);

        assertThat(create(draft(1, SNAPSHOT))).isEmpty();
    }

    // ---- reconciling refresh: the new parse is applied, so its record replaces the old one

    @Test
    void aReconcilingRefreshReplacesTheVersionTheTimeAndTheSnapshot() {
        Statement s = existing(1);

        StatementPersistenceService.Refreshed r = service.refreshReconciled(accountId, "sha", draft(2, SNAPSHOT), List.of(), Set.of()).orElseThrow();

        assertThat(r.statement()).isSameAs(s);
        assertThat(s.getParserVersion()).isEqualTo(2);
        assertThat(s.getParseRecordedAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        verify(repository).save(s);
    }

    @Test
    void aStatementParsedBeforeVersionsExistedGetsItsFirstRecordOnARefresh() {
        Statement s = existing(null);

        service.refreshReconciled(accountId, "sha", draft(1, SNAPSHOT), List.of(), Set.of()).orElseThrow();

        assertThat(s.getParserVersion()).isEqualTo(1);
    }

    @Test
    void aRefreshWhoseFiguresAllMatchStillRecordsTheNewParse() {
        Statement s = existing(1);
        s.setOpeningBalance(new BigDecimal("1000"));
        s.setClosingBalance(new BigDecimal("1500"));
        s.setTotalDebits(new BigDecimal("200"));
        s.setTotalCredits(new BigDecimal("700"));
        s.setTransactionCount(3);
        s.setLinesSkipped(0);
        s.setParseMode("balance_chain");
        s.setChainValidationPct(new BigDecimal("100"));
        s.setChecksumOk(true);
        s.setVerdict(StatementVerdict.AUTO_INGEST);

        StatementPersistenceService.Refreshed r = service.refreshReconciled(accountId, "sha", draft(2, SNAPSHOT), List.of(), Set.of()).orElseThrow();

        assertThat(r.changedFields()).isEmpty();
        assertThat(s.getParserVersion()).isEqualTo(2);
        verify(repository).save(s);
    }

    @Test
    void aRefreshFromADraftWithoutAVersionKeepsTheStoredRecord() {
        Statement s = existing(1);

        service.refreshReconciled(accountId, "sha", draft(null, null), List.of(), Set.of()).orElseThrow();

        assertThat(s.getParserVersion()).isEqualTo(1);
        assertThat(s.getParseRecordedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void aParseThatDoesNotFullyReconcileAppliesNothingAndKeepsTheRecord() {
        Statement s = existing(1);
        StatementDraft notReconciled = new StatementDraft("bank_account", START, END, null, null, "HDFC", "1234", 3, 0, null, null,
                "balance_chain", new BigDecimal("80"), true, StatementVerdict.NEEDS_REVIEW, null, null, Set.of(), 2, SNAPSHOT);

        assertThat(service.refreshReconciled(accountId, "sha", notReconciled, List.of(), Set.of())).isEmpty();

        assertThat(s.getParserVersion()).isEqualTo(1);
    }

    @Test
    void anOversizeSnapshotOnARefreshKeepsTheVersionAndLeavesTheDroppingToTheRecordsService() {
        Statement s = existing(1);
        String big = "SECRET-NARRATION" + "x".repeat(2 * 1024 * 1024);

        service.refreshReconciled(accountId, "sha", draft(2, big), List.of(), Set.of()).orElseThrow();

        assertThat(s.getParserVersion()).isEqualTo(2);

    }

    // ---- fill-blanks refresh: old lines stay, so the old record stays

    @Test
    void aFillBlanksRefreshNeverTouchesTheStoredRecord() {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(new User());
        s.setStatementType("credit_card");
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        s.setPeriodEnd(END);
        s.setParserVersion(1);
        s.setParseRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        s.setCreatedAt(Instant.now());
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));
        Map<String, Object> card = new HashMap<>();
        card.put("payment_due_date", LocalDate.of(2026, 10, 18));
        card.put("total_amount_due", new BigDecimal("999"));
        card.put("minimum_amount_due", new BigDecimal("25"));
        StatementDraft d = new StatementDraft("credit_card", START, END, null, new BigDecimal("999"), "HDFC", "1234", 3, 0, null, null,
                "heuristic", null, null, StatementVerdict.AUTO_INGEST, card, null, Set.of(), 5, SNAPSHOT);

        Optional<List<String>> filled = service.refreshFromReupload(accountId, "sha", d);

        assertThat(filled).isPresent();
        assertThat(filled.get()).isNotEmpty();
        assertThat(s.getParserVersion()).isEqualTo(1);
        assertThat(s.getParseRecordedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));


    }
}
