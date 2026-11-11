package com.financeos.domain.statement.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class StatementFileAttacherCopyTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private final UUID user = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final byte[] bytes = {1, 2, 3};
    private StatementFileStore store;
    private StatementRepository statements;
    private StatementFileAttacher attacher;

    @BeforeEach
    void setUp() {
        store = mock(StatementFileStore.class);
        statements = mock(StatementRepository.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        attacher = new StatementFileAttacher(store, statements, tm);
    }

    private static StatementDraft draft(LocalDate start, LocalDate end, String opening, String closing) {
        return new StatementDraft("bank_account", start, end, dec(opening), dec(closing), "Bank", "1234", 1, 0, null, null,
                "heuristic", null, null, null, null, null);
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private Statement stored(String opening, String closing) {
        Statement s = new Statement();
        s.setId(statementId);
        s.setPeriodStart(START);
        s.setPeriodEnd(END);
        s.setOpeningBalance(dec(opening));
        s.setClosingBalance(dec(closing));
        when(statements.findLiveWithoutFileByAccountIdAndPeriod(account, START, END)).thenReturn(Optional.of(s));
        when(statements.findById(statementId)).thenReturn(Optional.of(s));
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.of(fileId));
        return s;
    }

    @Test
    void theProvenStatementBecomesTheCopysFileAndHash() {
        stored("100.0", "250");
        when(statements.setFileAndShaIfMissing(statementId, fileId, "sha-new")).thenReturn(1);

        assertTrue(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100.00", "250.00"), bytes, "a.pdf", statementId));

        verify(statements).setFileAndShaIfMissing(statementId, fileId, "sha-new");
    }

    @Test
    void aNullSameStatementIdStoresNothing() {
        stored("100", "250");

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", null));

        verify(store, never()).store(any(), any(), any());
    }

    @Test
    void anotherStatementIdStoresNothing() {
        stored("100", "250");

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", UUID.randomUUID()));

        verify(store, never()).store(any(), any(), any());
    }

    @Test
    void aStatementWithAFileIsNeverTouched() {
        when(statements.findLiveWithoutFileByAccountIdAndPeriod(account, START, END)).thenReturn(Optional.empty());

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", statementId));

        verify(store, never()).store(any(), any(), any());
        verify(statements, never()).setFileAndShaIfMissing(any(), any(), any());
    }

    @Test
    void aDifferentPeriodDoesNothing() {
        stored("100", "250");

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new",
                draft(START, END.minusDays(1), "100", "250"), bytes, "a.pdf", statementId));

        verify(store, never()).store(any(), any(), any());
    }

    @Test
    void aMissingPeriodOrDraftReturnsFalse() {
        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", null, bytes, "a.pdf", statementId));
        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(null, END, "1", "2"), bytes, "a.pdf", statementId));
    }

    @Test
    void refusedWhenAnotherStatementHasThisHash() {
        stored("100", "250");
        when(statements.existsByAccountIdAndFileSha256(account, "sha-new")).thenReturn(true);

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", statementId));

        verify(store, never()).store(any(), any(), any());
    }

    @Test
    void anUpdateThatChangesNothingReturnsFalse() {
        stored("100", "250");
        when(statements.setFileAndShaIfMissing(statementId, fileId, "sha-new")).thenReturn(0);

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", statementId));
    }

    @Test
    void aUniqueKeyRaceReturnsFalseWithoutThrowing() {
        stored("100", "250");
        when(statements.setFileAndShaIfMissing(any(), any(), any())).thenThrow(new DataIntegrityViolationException("unique"));

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", statementId));
    }

    @Test
    void aStoreFailureReturnsFalseWithoutThrowing() {
        stored("100", "250");
        when(store.store(user, bytes, "a.pdf")).thenThrow(new RuntimeException("store down"));

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", statementId));
    }

    @Test
    void aStoreThatKeepsNothingReturnsFalse() {
        stored("100", "250");
        when(store.store(user, bytes, "a.pdf")).thenReturn(Optional.empty());

        assertFalse(attacher.attachCopyOfSameStatement(user, account, "sha-new", draft(START, END, "100", "250"), bytes, "a.pdf", statementId));
    }

    // ---- provenCopyTarget

    @Test
    void provenCopyTargetReturnsTheStatementWhenFiguresProve() {
        stored("100", "250");

        assertEquals(Optional.of(statementId), attacher.provenCopyTarget(account, draft(START, END, "100.00", "250")));
    }

    @Test
    void provenCopyTargetIsEmptyWhenNotProven() {
        stored("100", "250");

        assertEquals(Optional.empty(), attacher.provenCopyTarget(account, draft(START, END, "100", "999")));
    }

    @Test
    void provenCopyTargetIsEmptyWithNoFilelessStatementOrNoPeriod() {
        when(statements.findLiveWithoutFileByAccountIdAndPeriod(account, START, END)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), attacher.provenCopyTarget(account, draft(START, END, "100", "250")));
        assertEquals(Optional.empty(), attacher.provenCopyTarget(account, null));
        assertEquals(Optional.empty(), attacher.provenCopyTarget(account, draft(null, END, "100", "250")));
    }

    @Test
    void provenCopyTargetNeverThrows() {
        when(statements.findLiveWithoutFileByAccountIdAndPeriod(any(), any(), any())).thenThrow(new RuntimeException("db"));

        assertEquals(Optional.empty(), attacher.provenCopyTarget(account, draft(START, END, "100", "250")));
    }

    // ---- the figure proof

    private static Statement figures(String opening, String closing, String debits, String credits) {
        Statement s = new Statement();
        s.setOpeningBalance(dec(opening));
        s.setClosingBalance(dec(closing));
        s.setTotalDebits(dec(debits));
        s.setTotalCredits(dec(credits));
        return s;
    }

    private static StatementDraft draftOf(String opening, String closing, String debits, String credits, Map<String, Object> card) {
        return new StatementDraft("bank_account", START, END, dec(opening), dec(closing), "Bank", "1234", 1, 0, dec(debits), dec(credits),
                "heuristic", null, null, null, card, null);
    }

    @Test
    void twoEqualFiguresProve() {
        assertTrue(StatementFileAttacher.proveSameFigures(figures("10", "20", null, null), draftOf("10", "20", null, null, null)).holds());
    }

    @Test
    void oneDifferingFigureBreaksTheProof() {
        StatementFileAttacher.FigureProof p = StatementFileAttacher.proveSameFigures(
                figures("10", "20", "5", "6"), draftOf("10", "20", "5", "7", null));
        assertFalse(p.holds());
        assertEquals(4, p.compared());
        assertEquals(java.util.List.of("total_credits"), p.differing());
    }

    @Test
    void oneComparableFigureIsNotEnough() {
        assertFalse(StatementFileAttacher.proveSameFigures(figures("10", null, null, null), draftOf("10", "20", null, null, null)).holds());
    }

    @Test
    void scaleDoesNotMatter() {
        assertTrue(StatementFileAttacher.proveSameFigures(figures("100.0", "5.50", null, null), draftOf("100.00", "5.5", null, null, null)).holds());
    }

    @Test
    void cardTotalDueCounts() {
        Statement s = figures("10", null, null, null);
        StatementCreditCardDetails d = new StatementCreditCardDetails();
        d.setTotalAmountDue(new BigDecimal("40.0"));
        s.setCreditCardDetails(d);

        assertTrue(StatementFileAttacher.proveSameFigures(s, draftOf("10", null, null, null, Map.of("total_amount_due", new BigDecimal("40")))).holds());
        assertFalse(StatementFileAttacher.proveSameFigures(s, draftOf("10", null, null, null, Map.of("total_amount_due", 41))).holds());
    }

    @Test
    void totalDueIsNotCountedTwiceWithClosing() {
        Statement s = figures(null, "40", null, null);
        StatementCreditCardDetails d = new StatementCreditCardDetails();
        d.setTotalAmountDue(new BigDecimal("40"));
        s.setCreditCardDetails(d);

        StatementFileAttacher.FigureProof p = StatementFileAttacher.proveSameFigures(s, draftOf(null, "40", null, null, Map.of("total_amount_due", new BigDecimal("40"))));
        assertEquals(1, p.compared());
        assertFalse(p.holds());
    }

    @Test
    void totalDueCountsWhenClosingIsAbsentOnOneSide() {
        Statement s = figures("10", "40", null, null);
        StatementCreditCardDetails d = new StatementCreditCardDetails();
        d.setTotalAmountDue(new BigDecimal("40"));
        s.setCreditCardDetails(d);

        StatementFileAttacher.FigureProof p = StatementFileAttacher.proveSameFigures(s, draftOf("10", null, null, null, Map.of("total_amount_due", new BigDecimal("40"))));
        assertEquals(2, p.compared());
        assertTrue(p.holds());
    }

    @Test
    void aNonNumericCardValueIsIgnored() {
        Statement s = figures("10", null, null, null);
        StatementCreditCardDetails d = new StatementCreditCardDetails();
        d.setTotalAmountDue(new BigDecimal("40"));
        s.setCreditCardDetails(d);

        StatementFileAttacher.FigureProof p = StatementFileAttacher.proveSameFigures(s, draftOf("10", null, null, null, Map.of("total_amount_due", "n/a")));
        assertEquals(1, p.compared());
    }
}
