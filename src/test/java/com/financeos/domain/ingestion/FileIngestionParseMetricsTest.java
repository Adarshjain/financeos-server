package com.financeos.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.statement.StatementParseMetrics;
import com.financeos.statement.parser.StatementParseException.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The upload path counts each parsed file exactly once, at the point its outcome is known. */
class FileIngestionParseMetricsTest {

    private final StatementParser parser = mock(StatementParser.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FileIngestionService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new FileIngestionService(mock(AccountRepository.class), userRepository, parser,
                mock(FileIngestionDbHandler.class), mock(TransactionMatcher.class), mock(ReviewStatusManager.class),
                mock(CategorizationService.class), persistence, accountService);
        StatementBatchMatcher batchMatcher = mock(StatementBatchMatcher.class);
        service.setBatchMatcher(batchMatcher);
        service.setParseMetrics(new StatementParseMetrics(registry));
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        Account account = new Account();
        account.setId(accountId);
        account.setUser(user);
        account.setType(AccountType.credit_card);
        when(accountService.getAccountById(accountId)).thenReturn(account);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(batchMatcher.match(any(), any(), any(), any())).thenReturn(new StatementBatchMatcher.Result(0, 0, 0, 0, 0, List.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static StatementDraft draft(StatementVerdict verdict, String mode, Boolean checksumOk) {
        return new StatementDraft("bank_account", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, "Bank",
                "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, mode, null, checksumOk, verdict, null, null);
    }

    private void ingest(String content, StatementExtractionResult result) {
        byte[] bytes = content.getBytes();
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(result);
        service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));
    }

    private static StatementExtractionResult parsed(String number, LocalDate end, StatementDraft draft) {
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 9, 5), BigDecimal.TEN, "DEBIT", "Txn", null, null);
        return StatementExtractionResult.success(List.of(line), number, end == null ? null : end.minusDays(29), end, draft).withIssuerId("hdfc");
    }

    private double count(String outcome, String kind, String verdict, String issuer, String type) {
        var c = registry.find("financeos.statement.parse").tags("path", "upload", "outcome", outcome, "kind", kind,
                "verdict", verdict, "issuer", issuer, "type", type).counter();
        return c == null ? 0 : c.count();
    }

    private double total() {
        return registry.find("financeos.statement.parse").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void aParseFailureIsCountedOnceAsFailedWithItsKind() {
        ingest("a", StatementExtractionResult.failure("ERROR: no text layer found (scanned/image PDF?).", Kind.NO_TEXT_LAYER));

        assertThat(count("failed", "NO_TEXT_LAYER", "-", "unknown", "-")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void aFileWithNoLinesIsCountedOnceAsEmpty() {
        ingest("b", StatementExtractionResult.success(List.of(), null, null, null, null));

        assertThat(count("empty", "-", "-", "unknown", "-")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void aGateBlockIsCountedOnceAsBlockedWithTheReason() {
        ingest("c", parsed("1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.REJECTED, "opening_closing", Boolean.FALSE)));

        assertThat(count("blocked", "CONTRADICTED", "REJECTED", "hdfc", "bank_account")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void aFileWithNoNumberAndNoPeriodIsCountedAsBlockedNotAStatement() {
        ingest("d", parsed(null, null, draft(StatementVerdict.AUTO_INGEST, "heuristic", null)));

        assertThat(count("blocked", "NOT_A_STATEMENT", "AUTO_INGEST", "hdfc", "bank_account")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void anImportedStatementIsCountedOnceAsParsedWhateverHappensNext() {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(s));

        ingest("e", parsed("1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null)));

        assertThat(count("parsed", "-", "AUTO_INGEST", "hdfc", "bank_account")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void aDuplicateStatementIsStillCountedOnceAsParsed() {
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());

        ingest("f", parsed("1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.NEEDS_REVIEW, "heuristic", null)));

        assertThat(count("parsed", "-", "NEEDS_REVIEW", "hdfc", "bank_account")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void anEmptyFileIsNotAParseAndCountsNothing() {
        service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", new byte[0])));

        assertThat(total()).isZero();
    }

    @Test
    void aServiceBuiltWithoutTheMetricStillIngests() {
        service.setParseMetrics(null);

        ingest("g", StatementExtractionResult.failure("ERROR: failed to read PDF.", Kind.UNREADABLE));

        assertThat(total()).isZero();
    }
}
