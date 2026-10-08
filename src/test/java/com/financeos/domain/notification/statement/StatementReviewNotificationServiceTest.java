package com.financeos.domain.notification.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StatementReviewNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final StatementReviewNotificationService service =
            new StatementReviewNotificationService(statementRepository, transactionRepository, prefsLoader, settingsService,
                    StatementReviewNotificationService.DEFAULT_MIN_AGE_MINUTES);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
    private Account card;
    private Account bank;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(2, 5).atZone(IST).toInstant(), IST)); // 02:05 — no send-hour gate here
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("HDFC Regalia");
        card.setType(AccountType.credit_card);
        bank = new Account();
        bank.setId(UUID.randomUUID());
        bank.setName("SBI Savings");
        bank.setType(AccountType.bank_account);
        when(settingsService.deliver(any(), any())).thenReturn(1);
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(settings, true, 9, List.of(7, 3, 1, 0), kinds));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Statement statement(Account account, String type, LocalDate start, LocalDate end) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setAccount(account);
        s.setStatementType(type);
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setCreatedAt(TODAY.minusDays(1).atStartOfDay(IST).toInstant());
        return s;
    }

    private void candidates(Statement... statements) {
        when(statementRepository.findReviewDigestCandidates(eq(userId), any(), any())).thenReturn(List.of(statements));
    }

    private PushMessage delivered() {
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        return message.getValue();
    }

    @Test
    void looksAtStatementsBetweenFourteenDaysAndOneHourOld() {
        candidates();
        service.evaluate(userId);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(statementRepository).findReviewDigestCandidates(eq(userId), from.capture(), to.capture());
        Instant now = TODAY.atTime(2, 5).atZone(IST).toInstant();
        assertEquals(now.minus(Duration.ofDays(14)), from.getValue());
        assertEquals(now.minus(Duration.ofHours(1)), to.getValue());
        verify(prefsLoader, never()).load(any());
    }

    @Test
    void cardStatementDigestCarriesTheBillAndDeepLinksToThePeriod() {
        Statement s = statement(card, "credit_card", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setTotalAmountDue(new BigDecimal("45230"));
        d.setPaymentDueDate(LocalDate.of(2026, 10, 18));
        s.setCreditCardDetails(d);
        candidates(s);
        when(transactionRepository.countNeedsReviewInPeriod(eq(card.getId()), eq(s.getPeriodStart()), eq(s.getPeriodEnd()), anyCollection())).thenReturn(7L);

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));

        PushMessage message = delivered();
        assertEquals("HDFC Regalia Sep statement: 7 transactions didn't reconcile", message.title());
        assertEquals("₹45,230 due 18 Oct · Review and approve or merge the leftovers.", message.body());
        assertEquals("/transactions/review?account=" + card.getId() + "&from=2026-09-01&to=2026-09-30", message.url());
        assertEquals("statement-review-" + s.getId(), message.tag());
        assertEquals(TODAY, s.getReviewNotifiedOn());
        verify(statementRepository).save(s);
        verify(transactionRepository).countNeedsReviewInPeriod(card.getId(), s.getPeriodStart(), s.getPeriodEnd(),
                StatementReviewNotificationService.DANGLING_REASONS);
    }

    @Test
    void bankStatementDigestNamesThePeriodInstead() {
        Statement s = statement(bank, "bank", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        candidates(s);
        when(transactionRepository.countNeedsReviewInPeriod(any(), any(), any(), anyCollection())).thenReturn(1L);

        service.evaluate(userId);

        PushMessage message = delivered();
        assertEquals("SBI Savings Sep statement: 1 transaction didn't reconcile", message.title());
        assertEquals("Period 1 Sep – 30 Sep · Review and approve or merge the leftovers.", message.body());
    }

    @Test
    void cleanStatementsAreStampedSilently() {
        Statement s = statement(bank, "bank", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        candidates(s);
        when(transactionRepository.countNeedsReviewInPeriod(any(), any(), any(), anyCollection())).thenReturn(0L);

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        assertNotNull(s.getReviewNotifiedOn());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void aStatementWithoutAPeriodIsStampedWithoutCounting() {
        Statement s = statement(bank, "bank", null, null);
        candidates(s);

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        assertNotNull(s.getReviewNotifiedOn());
        verify(transactionRepository, never()).countNeedsReviewInPeriod(any(), any(), any(), anyCollection());
    }

    @Test
    void kindOffOrPushOffStillStamps() {
        kinds.put(NotificationKind.STATEMENT_REVIEW_DIGEST, false);
        Statement s = statement(card, "credit_card", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        candidates(s);
        when(transactionRepository.countNeedsReviewInPeriod(any(), any(), any(), anyCollection())).thenReturn(3L);

        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertNotNull(s.getReviewNotifiedOn());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void cardWithoutParsedTotalFallsBackToThePeriodLine() {
        Statement s = statement(card, "credit_card", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        s.setCreditCardDetails(new StatementCreditCardDetails(s));
        assertEquals("Period 1 Sep – 30 Sep · Review and approve or merge the leftovers.", StatementReviewMessages.digest(s, 2).body());
    }
}
