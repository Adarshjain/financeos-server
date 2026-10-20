package com.financeos.domain.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Re-uploading a card statement that lacks figures fills only the empty ones. */
class StatementPersistenceFillTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final LocalDate DUE = LocalDate.of(2026, 10, 18);

    private final StatementRepository repository = mock(StatementRepository.class);
    private final StatementPersistenceService service = new StatementPersistenceService(repository,
            mock(StatementTransactionRepository.class), mock(ApplicationEventPublisher.class));
    private final UUID accountId = UUID.randomUUID();

    private Statement existing(LocalDate start, LocalDate end, LocalDate due, BigDecimal total, BigDecimal min) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(new User());
        s.setStatementType("credit_card");
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setCreatedAt(Instant.now());
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setPaymentDueDate(due);
        d.setTotalAmountDue(total);
        d.setMinimumAmountDue(min);
        s.setCreditCardDetails(d);
        return s;
    }

    private StatementDraft draft(String type, LocalDate start, LocalDate end, LocalDate due, BigDecimal total, BigDecimal min) {
        Map<String, Object> card = new HashMap<>();
        card.put("payment_due_date", due);
        card.put("total_amount_due", total);
        card.put("minimum_amount_due", min);
        return new StatementDraft(type, start, end, null, total, "HDFC", "1234", 3, 0, null, null, "heuristic", null, null,
                StatementVerdict.AUTO_INGEST, card);
    }

    @Test
    void fillsOnlyTheEmptyFieldsOfTheSameFile() {
        Statement s = existing(null, END, null, new BigDecimal("500"), null);
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));

        Optional<List<String>> result = service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, DUE, new BigDecimal("999"), new BigDecimal("25")));

        assertEquals(List.of("PERIOD_UPDATED", "DUE_DATE", "MINIMUM_DUE"), result.orElseThrow());
        assertEquals(START, s.getPeriodStart());
        assertEquals(DUE, s.getCreditCardDetails().getPaymentDueDate());
        assertEquals(new BigDecimal("25"), s.getCreditCardDetails().getMinimumAmountDue());
        assertEquals(new BigDecimal("500"), s.getCreditCardDetails().getTotalAmountDue(), "a present value is never overwritten");
        verify(repository).save(s);
    }

    @Test
    void matchesByAccountAndPeriodEndWhenTheFileDiffers() {
        Statement s = existing(null, END, null, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "other")).thenReturn(List.of());
        when(repository.findByAccountIdAndPeriodEnd(accountId, END)).thenReturn(List.of(s));

        Optional<List<String>> result = service.refreshFromReupload(accountId, "other",
                draft("credit_card", START, END, DUE, new BigDecimal("500"), new BigDecimal("25")));

        assertEquals(List.of("PERIOD_UPDATED", "DUE_DATE"), result.orElseThrow());
        assertEquals(START, s.getPeriodStart());
    }

    @Test
    void fillsTheTotalAndClosingBalanceWhenBothAreEmpty() {
        Statement s = existing(START, END, DUE, null, new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));

        service.refreshFromReupload(accountId, "sha", draft("credit_card", START, END, DUE, new BigDecimal("800"), new BigDecimal("40")));

        assertEquals(new BigDecimal("800"), s.getCreditCardDetails().getTotalAmountDue());
        assertEquals(new BigDecimal("800"), s.getClosingBalance());
        assertEquals(new BigDecimal("25"), s.getCreditCardDetails().getMinimumAmountDue());
    }

    @Test
    void theParsedPeriodReplacesAGuessedOneAndAMissingEndIsFilled() {
        Statement guessed = existing(START.plusDays(3), END.minusDays(2), null, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(guessed));
        assertEquals(List.of("PERIOD_UPDATED"), service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, null, null, null)).orElseThrow());
        assertEquals(START, guessed.getPeriodStart());
        assertEquals(END, guessed.getPeriodEnd());

        Statement noEnd = existing(null, null, DUE, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha2")).thenReturn(List.of(noEnd));
        assertEquals(List.of("PERIOD_END"), service.refreshFromReupload(accountId, "sha2",
                draft("credit_card", START, END, DUE, null, null)).orElseThrow());
        assertEquals(END, noEnd.getPeriodEnd());
    }

    @Test
    void aParsedEndWithoutAStartClearsTheGuessedStart() {
        Statement guessed = existing(START.plusDays(3), END.minusDays(2), null, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(guessed));

        assertEquals(List.of("PERIOD_UPDATED", "DUE_DATE"), service.refreshFromReupload(accountId, "sha",
                draft("credit_card", null, END, DUE, null, null)).orElseThrow());
        assertEquals(null, guessed.getPeriodStart());
        assertEquals(END, guessed.getPeriodEnd());
    }

    @Test
    void nothingNewWhenTheFileLacksWhatIsMissing() {
        Statement s = existing(START, END, null, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));

        Optional<List<String>> result = service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, null, new BigDecimal("500"), new BigDecimal("25")));

        assertTrue(result.orElseThrow().isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void aCompleteDuplicateWithTheSamePeriodHasNothingNewToFill() {
        Statement s = existing(START, END, DUE, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));

        assertTrue(service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, DUE, new BigDecimal("1"), new BigDecimal("1"))).orElseThrow().isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void aCompleteStatementGetsItsGuessedPeriodReplaced() {
        Statement s = existing(START.plusDays(3), END.minusDays(2), DUE, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));
        when(repository.findByAccountIdAndPeriodEnd(accountId, END)).thenReturn(List.of());

        assertEquals(List.of("PERIOD_UPDATED"), service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, DUE, new BigDecimal("1"), new BigDecimal("1"))).orElseThrow());
        assertEquals(START, s.getPeriodStart());
        assertEquals(END, s.getPeriodEnd());
        assertEquals(new BigDecimal("500"), s.getCreditCardDetails().getTotalAmountDue());
        verify(repository).save(s);
    }

    @Test
    void thePeriodIsKeptWhenAnotherStatementAlreadyEndsOnTheNewEnd() {
        Statement s = existing(START.plusDays(3), END.minusDays(2), DUE, new BigDecimal("500"), new BigDecimal("25"));
        Statement other = existing(START, END, DUE, new BigDecimal("1"), new BigDecimal("1"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));
        when(repository.findByAccountIdAndPeriodEnd(accountId, END)).thenReturn(List.of(other));

        assertTrue(service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, DUE, null, null)).orElseThrow().isEmpty());
        assertEquals(END.minusDays(2), s.getPeriodEnd());
        assertEquals(START.plusDays(3), s.getPeriodStart());
        verify(repository, never()).save(any());
    }

    @Test
    void anInvalidParsedPeriodIsNotApplied() {
        Statement s = existing(START, END, DUE, new BigDecimal("500"), new BigDecimal("25"));
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));

        assertTrue(service.refreshFromReupload(accountId, "sha",
                draft("credit_card", END.plusDays(5), END.plusDays(1), DUE, null, null)).orElseThrow().isEmpty());
        assertTrue(service.refreshFromReupload(accountId, "sha",
                draft("credit_card", END.minusDays(401), END.plusDays(1), DUE, null, null)).orElseThrow().isEmpty());
        assertEquals(START, s.getPeriodStart());
        assertEquals(END, s.getPeriodEnd());
        verify(repository, never()).save(any());
    }

    @Test
    void aRejectedStatementIsNotTouched() {
        Statement s = existing(START.plusDays(3), END.minusDays(2), null, null, null);
        s.setVerdict(StatementVerdict.REJECTED);
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));
        when(repository.findByAccountIdAndPeriodEnd(accountId, END)).thenReturn(List.of());

        assertTrue(service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, DUE, BigDecimal.TEN, BigDecimal.ONE)).isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void noMatchAndBankStatementsAreNotTouched() {
        assertTrue(service.refreshFromReupload(accountId, "sha",
                draft("credit_card", START, END, DUE, BigDecimal.TEN, BigDecimal.ONE)).isEmpty());
        assertTrue(service.refreshFromReupload(accountId, "sha",
                draft("bank_account", START, END, DUE, BigDecimal.TEN, BigDecimal.ONE)).isEmpty());
        verify(repository, never()).save(any());
    }
}
